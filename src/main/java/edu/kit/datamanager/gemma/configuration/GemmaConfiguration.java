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
package edu.kit.datamanager.gemma.configuration;

import edu.kit.datamanager.configuration.GenericPluginProperties;
import java.util.Map;
import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 *
 * @author jejkal
 */
@Configuration
@Data
public class GemmaConfiguration extends GenericPluginProperties{

  @Value("#{${repo.plugin.gemma.schemaMapping}}")
  private Map<String, String> schemaMapping;

  @Value("${repo.plugin.gemma.mappingsLocation}")
  private String mappingsLocation;

  @Value("${repo.plugin.gemma.pythonLocation}")
  private String pythonLocation;

  @Value("${repo.plugin.gemma.gemmaLocation}")
  private String gemmaLocation;

}
