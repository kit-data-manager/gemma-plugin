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
package edu.kit.datamanager.gemma.plugin;

import edu.kit.datamanager.clients.SimpleServiceClient;
import edu.kit.datamanager.clients.UploadClient;
import edu.kit.datamanager.clients.impl.SimpleRepositoryClient;
import edu.kit.datamanager.entities.messaging.BasicMessage;
import edu.kit.datamanager.entities.messaging.DataResourceMessage;
import edu.kit.datamanager.entities.repo.ContentInformation;
import edu.kit.datamanager.gemma.configuration.GemmaConfiguration;
import edu.kit.datamanager.gemma.util.PythonUtils;
import edu.kit.datamanager.messaging.client.handler.IMessageHandler;
import edu.kit.datamanager.messaging.client.util.MessageHandlerUtils;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

/**
 *
 * @author jejkal
 */
@Component
public class GemmaHandler implements IMessageHandler{

  private final static Logger LOGGER = LoggerFactory.getLogger(GemmaHandler.class);

  @Autowired
  private GemmaConfiguration gemmaConfiguration;

  @Override
  public boolean configure(){
    String baseUrl = gemmaConfiguration.getRepositoryBaseUrl();

    if(gemmaConfiguration.getGemmaLocation() == null){
      LOGGER.trace("Gemma location is missing. Unable to configure handler.");
      return false;
    }
    LOGGER.trace("Checking Gemma location property {}.", gemmaConfiguration.getGemmaLocation());
    Path gemmaPath = Paths.get(gemmaConfiguration.getGemmaLocation());

    boolean gemmaFound = Files.exists(gemmaPath) && Files.isReadable(gemmaPath);

    if(gemmaConfiguration.getPythonLocation() == null){
      LOGGER.trace("Python location is missing. Unable to configure handler.");
      return false;
    }

    Path pythonPath = Paths.get(gemmaConfiguration.getPythonLocation());

    boolean pythonFound = PythonUtils.run(gemmaConfiguration.getPythonLocation(), "--version") == 0;

    if(!pythonFound){
      LOGGER.error("Unable to test Python installation at {}.", gemmaConfiguration.getPythonLocation());
    }

    String mappingsLocation = gemmaConfiguration.getMappingsLocation();

    if(mappingsLocation == null){
      LOGGER.trace("Mappings location is missing. Unable to configure handler.");
      return false;
    }

    LOGGER.trace("Checking for configured mappings.");
    Map<String, String> mappings = gemmaConfiguration.getSchemaMappings();
    boolean mappingError = true;
    if(mappingsLocation != null && mappings != null){
      LOGGER.trace("Mappings found in configuration, checking mapping files.");
      Set<Entry<String, String>> entrySet = mappings.entrySet();

      for(Entry<String, String> entry : entrySet){
        LOGGER.trace("Checking mapping file for key {}.", entry.getKey());
        String mappingFilename = entry.getValue();
        Path mappingPath = Paths.get(mappingsLocation, mappingFilename);
        if(!Files.exists(mappingPath) || !Files.isReadable(mappingPath)){
          LOGGER.error("Unable to find/read mapping file at path {}.", mappingPath);
          mappingError = true;
          break;
        } else{
          //if we arrive here, at least one mapping was found
          mappingError = false;
        }
      }
    }

    if(mappingsLocation == null){
      LOGGER.error("No mappingsLocation provided, unable to search for mapping files.");
    }

    if(mappings == null){
      LOGGER.error("No mappings provided, at least one mapping is required.");
    }

    return baseUrl != null && !mappingError && gemmaFound && pythonFound;
  }

  @Override
  public RESULT handle(BasicMessage message){
    RESULT result = RESULT.REJECTED;

    if(!MessageHandlerUtils.isAddressed(getHandlerIdentifier(), message)){
      LOGGER.trace("Handler {} is not addressed by message with addressees {}. Rejecting message.", getHandlerIdentifier(), message.getAddressees());
      return result;
    }

    if(DataResourceMessage.SUB_CATEGORY.DATA.getValue().equals(message.getSubCategory())){
      //Check uploader for handlerIdentifier() to avoid recursion.
      String pathProperty = message.getMetadata().get(DataResourceMessage.CONTENT_PATH_PROPERTY);
      if(MessageHandlerUtils.wasUploadedByPrincipal(gemmaConfiguration.getRepositoryBaseUrl(), message.getEntityId(), pathProperty, getHandlerIdentifier())){
        LOGGER.trace("Resource has already been processed. Skipping message.");
        return result;
      }

      result = handleContentInformationEvent(message);
    } else{
      //for all other events process data resource metadata
      result = handleDataResourceEvent(message);
    }

    return result;
  }

  /**
   * Handler for basic metadata events, e.g. create or update. This method
   * checks if there is a mapping for
   * 'application/vnd.datamanager.data-resource+json', which represents the
   * basic metadata. Afterwards, the repository is queried for the metadata
   * resource and the result is written to a temporary file. Finally, the
   * mapping is applied to the temporary file and the result is uploaded to the
   * repository. The message will be rejected if no mapping for content metadata
   * was found. In all other cases, the processing will be successful or fail.
   *
   * @param message The received message.
   *
   * @return The final result of the handling process.
   */
  private RESULT handleDataResourceEvent(BasicMessage message){
    LOGGER.trace("Calling handleDataResourceEvent({}),", message);

    String contentType = "application/vnd.datamanager.data-resource+json";
    if(!hasMapping(contentType)){
      LOGGER.trace("No mapping found for data resource content type {}. Configured mappings are: {}.", contentType, gemmaConfiguration.getSchemaMappings());
      return RESULT.REJECTED;
    }

    String theResource = SimpleServiceClient.create(gemmaConfiguration.getRepositoryBaseUrl()).withResourcePath(message.getEntityId()).accept(MediaType.TEXT_PLAIN).getResource(String.class);

//    RestTemplate restTemplate = new RestTemplate();
//    HttpHeaders headers = new HttpHeaders();
//    headers.setAccept(Arrays.asList(MediaType.APPLICATION_JSON));
//
//    HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(headers);
//    String destinationUri = gemmaConfiguration.getRepositoryBaseUrl() + message.getEntityId();
//
//    UriComponentsBuilder uriBuilder = UriComponentsBuilder.fromHttpUrl(destinationUri);
//
//    destinationUri = uriBuilder.toUriString();
//
//    LOGGER.trace("Performing HTTP GET to {}.", destinationUri);
    // ResponseEntity<String> response = restTemplate.exchange(destinationUri, HttpMethod.GET, requestEntity, String.class);
    Path metadataPath = Paths.get(System.getProperty("java.io.tmpdir"), message.getEntityId() + "_metadata.json");

    if(theResource != null){
      try{
        LOGGER.trace("Writing data resource to temporary file {}.", metadataPath);
        new FileOutputStream(metadataPath.toFile()).write(theResource.getBytes());
      } catch(IOException ex){
        LOGGER.error("Failed to write data resource to temporary file.", ex);
        return RESULT.FAILED;
      }
    } else{
      LOGGER.error("Did not receive any resource in the response body. Unable to continue.");
      return RESULT.FAILED;
    }

//    if(HttpStatus.OK.equals(response.getStatusCode())){
//      LOGGER.trace("Getting data resource from response body.");
//      String theResource = response.getBody();
//      if(theResource == null){
//        LOGGER.error("Did not receive any resource in the response body. Unable to continue.");
//        return RESULT.FAILED;
//      }
//
//      try{
//        LOGGER.trace("Writing data resource to temporary file {}.", metadataPath);
//        new FileOutputStream(metadataPath.toFile()).write(theResource.getBytes());
//      } catch(IOException ex){
//        LOGGER.error("Failed to write data resource to temporary file.", ex);
//        return RESULT.FAILED;
//      }
//
//      //transform file
//    } else{
//      LOGGER.error("Request to resource with identifier {} returned status {}. Message handling failed.", message.getEntityId(), response.getStatusCode());
//      return RESULT.FAILED;
//    }
    return applyAndUploadMapping(metadataPath.toUri(), contentType, message.getEntityId(), message.getEntityId() + "_metadata.json");
  }

  /**
   * Handler for content events, e.g. a file upload message was received. This
   * method will check the contentType and tries to find an appropriate mapping.
   * If one exists, it is checked whether the content is a local file. If this
   * is also the case, the mapping is applied and the result is uploaded to the
   * repository. Otherwise, the message is rejected.
   *
   * @param message The received message.
   *
   * @return The final result of the handling process.
   */
  private RESULT handleContentInformationEvent(BasicMessage message){
    //obtain content type
    LOGGER.trace("Calling handleContentInformationEvent({}),", message);
    String contentType = message.getMetadata().get(DataResourceMessage.CONTENT_TYPE_PROPERTY);
    LOGGER.trace("Checking for mapping file for content type {}.", contentType);
    if(!hasMapping(contentType)){
      LOGGER.trace("No mapping found for content type {}. Configured mappings are: {}.", contentType, gemmaConfiguration.getSchemaMappings());
      return RESULT.REJECTED;
    }

    String contentUri = message.getMetadata().get(DataResourceMessage.CONTENT_URI_PROPERTY);
    String relativePath = message.getMetadata().get(DataResourceMessage.CONTENT_PATH_PROPERTY);
    if(relativePath == null){
      LOGGER.error("Unable to obtain relative path property from data message. Message processing will fail.");
      return RESULT.FAILED;
    }

    String filename = (relativePath.contains("/")) ? relativePath.substring(relativePath.lastIndexOf("/")) : relativePath;
    LOGGER.trace("Checking scheme of content URI {}.", contentUri);
    URI content = URI.create(contentUri);

    if(!"file".equals(content.getScheme())){
      LOGGER.trace("ContentUri {} has a scheme different from 'file'. Processing not supported.", contentUri);
      return RESULT.REJECTED;
    }

    return applyAndUploadMapping(content, contentType, message.getEntityId(), filename);
  }

  /**
   * Apply a mapping to a metadata file and upload the result to the repository.
   * The appropriate mapping is obtained using the provided contentType, the
   * mapped content is accessible via contentUri, which is expected to be a
   * local file. Finally, the upload destination is determined by entityId and
   * filename.
   *
   * @param contentUri The location of the content.
   * @param contentType The contentType of the content accessible at contentUri.
   * @param entityId The resource identifier the content is related to.
   * @param filename The content filename, which can be different from the local
   * filename in contentUri.
   *
   * @return The final result, which can be returned as final handler result.
   */
  private RESULT applyAndUploadMapping(URI contentUri, String contentType, String entityId, String filename){
    LOGGER.trace("Calling processAndUploadMapping({}, {}, {}).", contentType, contentUri, entityId);
    Path mappingFile = getMappingFile(contentType);
    LOGGER.trace("Obtained mapping file {}. Obtaining output filename.", mappingFile);
    Path contentPath = Paths.get(contentUri);
    if(filename.contains(".")){
      LOGGER.trace("Replacing file extension of filename {}.", filename);
      filename = filename.substring(0, filename.lastIndexOf(".")) + ".elastic.json";
    } else{
      LOGGER.trace("Appending file extension to filename {}.", filename);
      filename += ".elastic.json";
    }

    LOGGER.trace("Obtained output filename '{}'. Creating Python mapping process.", filename);
    ByteArrayOutputStream bout = new ByteArrayOutputStream();
    int returnCode = PythonUtils.run(gemmaConfiguration.getPythonLocation(), gemmaConfiguration.getGemmaLocation(), bout, bout, mappingFile.toAbsolutePath().toString(), contentPath.toAbsolutePath().toString(), System.getProperty("java.io.tmpdir") + "/" + filename);
    LOGGER.trace(bout.toString());
    LOGGER.trace("Python mapping process returned with status {}. Uploading content to repository.", returnCode);

    Path localFile = Paths.get(System.getProperty("java.io.tmpdir"), filename);

    if(returnCode == 0){
      try{
        String elasticFilename = localFile.getName(localFile.getNameCount() - 1).toString();
        if(uploadContent(entityId, "generated/" + elasticFilename, localFile.toUri())){
          return RESULT.SUCCEEDED;
        }
      } catch(IOException ex){
        LOGGER.error("Failed to upload generated content.", ex);
      }
    }

    return RESULT.FAILED;
  }

  /**
   * Check if there is a mapping registered for the provided contentType.
   *
   * @param contentType The content type to check.
   *
   * @return TRUE if a mapping exists, FALSE otherwise.
   */
  private boolean hasMapping(String contentType){
    return gemmaConfiguration.getSchemaMappings().containsKey(contentType);
  }

  /**
   * Obtain the mapping file path for the provided content type. The path is
   * taken from the gemma configuration by concatenating the mappingsLocation
   * and the schemaMapping value for the provided contentType.
   *
   * @param contentType The content type to obtain the mapping file location
   * for.
   *
   * @return The mapping file location.
   */
  private Path getMappingFile(String contentType){
    return Paths.get(gemmaConfiguration.getMappingsLocation(), gemmaConfiguration.getSchemaMappings().get(contentType));
  }

  /**
   * Upload the mapped content to the repository. The mapped JSON file will be
   * placed at {resourceId}/data/generated/{filename} and will point to
   * localFileUri.
   *
   * @param resourceId The resource id the uploaded file is associated with.
   * @param filename The filename of the target file.
   * @param localFileUri The location of the file on the local file system.
   *
   * @return TRUE if the upload succeeded, FALSE otherwise.
   *
   * @throw IOException If the preparation of the upload failed.
   */
  private boolean uploadContent(String resourceId, String filename, URI localFileUri) throws IOException{
    LOGGER.trace("Performing uploadContent({}, {}, {}, {}).", resourceId, filename, localFileUri);
    ContentInformation info = new ContentInformation();
    LOGGER.trace("Setting uploader to handler identifier {}.", getHandlerIdentifier());
    info.setUploader(getHandlerIdentifier());

    HttpStatus status = SimpleRepositoryClient.create(gemmaConfiguration.getRepositoryBaseUrl()).uploadData(resourceId, filename, new File(localFileUri), info);
    return HttpStatus.CREATED.equals(status);
  }
}
