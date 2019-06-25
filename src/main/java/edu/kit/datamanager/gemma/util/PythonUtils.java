/*
 * Copyright 2019 Karlsruhe Institute of Technology.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package edu.kit.datamanager.gemma.util;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author jejkal
 */
public class PythonUtils{

  public static final int PYTHON_NOT_FOUND_ERROR = -2;
  public static final int TIMEOUT_ERROR = -3;
  public static final int EXECUTION_ERROR = -4;

  private final static Logger LOGGER = LoggerFactory.getLogger(PythonUtils.class);

  /**
   * Run the script at 'scriptLocation' with 'arguments' using the Python
   * executable at 'pythonLocation'. All output will be redirected to stdout and
   * stderr.
   *
   * @param pythonLocation The absolute path to a local python executable.
   * @param scriptLocation The absolute path to the python script which should
   * be executed.
   * @param arguments Veriable number of arguments, which can also be omitted.
   *
   * @return The exit status of the python process or one of the internal codes
   * PYTHON_NOT_FOUND, TIMEOUT_ERROR or EXECUTION_ERROR.
   */
  public static int run(String pythonLocation, String scriptLocation, String... arguments){
    return run(pythonLocation, scriptLocation, System.out, System.err, arguments);
  }

  /**
   * Run the script at 'scriptLocation' with 'arguments' using the Python
   * executable at 'pythonLocation'. The output as well as all errors can be
   * redirected to the provided output stream.
   *
   * @param pythonLocation The absolute path to a local python executable.
   * @param scriptLocation The absolute path to the python script which should
   * be executed.
   * @param output The stream receiving all process output.
   * @param error The stream receiving all process error output (can be equal to
   * 'output').
   * @param arguments Veriable number of arguments, which can also be omitted.
   *
   * @return The exit status of the python process or one of the internal codes
   * PYTHON_NOT_FOUND, TIMEOUT_ERROR or EXECUTION_ERROR.
   */
  public static int run(String pythonLocation, String scriptLocation, OutputStream output, OutputStream error, String... arguments){
    List<String> command = new ArrayList<>();
    command.add(pythonLocation);
    command.add(scriptLocation);

    Collections.addAll(command, arguments);

    ExecutorService pool = Executors.newSingleThreadExecutor();

    int result = 4711;
    try{
      ProcessBuilder pb = new ProcessBuilder(command.toArray(new String[]{}));
      Process p = pb.start();

      Future<List<String>> errorFuture = pool.submit(new ProcessReadTask(p.getErrorStream()));
      Future<List<String>> inputFuture = pool.submit(new ProcessReadTask(p.getInputStream()));

      List<String> stdErr = errorFuture.get(30, TimeUnit.SECONDS);
      List<String> stdOut = inputFuture.get(30, TimeUnit.SECONDS);

      for(String line : stdOut){
        if(output != null){
          output.write((line + "\n").getBytes());
        } else{
          LOGGER.trace("[OUT] {}", line);
        }
      }

      for(String line : stdErr){
        if(error != null){
          error.write((line + "\n").getBytes());
        } else{
          LOGGER.trace("[ERR] {}", line);
        }
      }

      result = p.waitFor();
    } catch(IOException ioe){
      if(ioe.getMessage().contains("No such file")){
        LOGGER.error("Failed to execute python.", ioe);
        result = PYTHON_NOT_FOUND_ERROR;
      } else{
        LOGGER.error("Failed to execute python script due to an unknown IOException.", ioe);
        result = EXECUTION_ERROR;
      }
    } catch(TimeoutException te){
      LOGGER.error("Python script did not return in expected timeframe of 30 seconds", te);
      result = TIMEOUT_ERROR;
    } catch(InterruptedException | ExecutionException e){
      LOGGER.error("Failed to execute python script due to an unknown Exception.", e);
      result = EXECUTION_ERROR;
    } finally{
      pool.shutdown();
    }
    return result;
  }

//  public static void main(String[] args){
//
//    ByteArrayOutputStream output = new ByteArrayOutputStream();
//
//    System.out.println(PythonUtils.run("/Users/jejkal/anaconda/bin/python", "/Users/jejkal/NetBeansProjects/KITDM-2.0/gemma/retrieve_response.py", System.out, System.err, "./testmd"));
//
//    //System.out.println("PRINTE " + output.toString());
//  }

  private static class ProcessReadTask implements Callable<List<String>>{

    private final InputStream inputStream;

    public ProcessReadTask(InputStream inputStream){
      this.inputStream = inputStream;
    }

    @Override
    public List<String> call(){
      return new BufferedReader(new InputStreamReader(inputStream))
              .lines()
              .collect(Collectors.toList());
    }
  }

}
