/*
 * Copyright © 2015 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package co.cask.cdap.etl.common;

import co.cask.cdap.api.DatasetConfigurer;
import co.cask.cdap.api.data.stream.Stream;
import co.cask.cdap.api.dataset.Dataset;
import co.cask.cdap.api.dataset.DatasetProperties;
import co.cask.cdap.api.dataset.module.DatasetModule;
import co.cask.cdap.api.plugin.PluginConfigurer;
import co.cask.cdap.api.plugin.PluginProperties;
import co.cask.cdap.api.plugin.PluginSelector;
import co.cask.cdap.etl.api.Engine;
import co.cask.cdap.etl.api.MultiInputPipelineConfigurer;
import co.cask.cdap.etl.api.MultiInputStageConfigurer;
import co.cask.cdap.etl.api.MultiOutputPipelineConfigurer;
import co.cask.cdap.etl.api.MultiOutputStageConfigurer;
import co.cask.cdap.etl.api.PipelineConfigurer;
import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Table;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Configurer for a pipeline, that delegates all operations to a PluginConfigurer, except it prefixes plugin ids
 * to provide isolation for each etl stage. For example, a source can use a plugin with id 'jdbcdriver' and
 * a sink can also use a plugin with id 'jdbcdriver' without clobbering each other.
 *
 * @param <C> type of the platform configurer
 */
public class DefaultPipelineConfigurer<C extends PluginConfigurer & DatasetConfigurer>
  implements PipelineConfigurer, MultiInputPipelineConfigurer, MultiOutputPipelineConfigurer {
  private final Engine engine;
  private final C configurer;
  private final String stageName;
  private final DefaultStageConfigurer stageConfigurer;
  private final Map<String, String> properties;
  private Table<String, String, String> propertiesFromStages;
  private transient final Logger LOG = LoggerFactory.getLogger(DefaultPipelineConfigurer.class);

  public DefaultPipelineConfigurer(C configurer, String stageName, Engine engine) {
    this.configurer = configurer;
    this.stageName = stageName;
    this.stageConfigurer = new DefaultStageConfigurer();
    this.engine = engine;
    this.properties = new HashMap<>();
    this.propertiesFromStages = HashBasedTable.create();
    LOG.warn("DEFAULTPIPELINECONFIGURER CREATED");
  }

  public DefaultPipelineConfigurer(C configurer, String stageName, Engine engine, Table<String, String, String> pipelinePropertiesTillPrevStage) {
    this(configurer, stageName, engine);
    this.propertiesFromStages = pipelinePropertiesTillPrevStage;
  }


  @Override
  public void addStream(Stream stream) {
    configurer.addStream(stream);
  }

  @Override
  public void addStream(String streamName) {
    configurer.addStream(streamName);
  }

  @Override
  public void addDatasetModule(String moduleName, Class<? extends DatasetModule> moduleClass) {
    configurer.addDatasetModule(moduleName, moduleClass);
  }

  @Override
  public void addDatasetType(Class<? extends Dataset> datasetClass) {
    configurer.addDatasetType(datasetClass);
  }

  @Override
  public void createDataset(String datasetName, String typeName, DatasetProperties properties) {
    configurer.createDataset(datasetName, typeName, properties);
  }

  @Override
  public void createDataset(String datasetName, String typeName) {
    configurer.createDataset(datasetName, typeName);
  }

  @Override
  public void createDataset(String datasetName, Class<? extends Dataset> datasetClass, DatasetProperties props) {
    configurer.createDataset(datasetName, datasetClass, props);
  }

  @Override
  public void createDataset(String datasetName, Class<? extends Dataset> datasetClass) {
    configurer.createDataset(datasetName, datasetClass);
  }

  @Nullable
  @Override
  public <T> T usePlugin(String pluginType, String pluginName, String pluginId, PluginProperties properties) {
    return configurer.usePlugin(pluginType, pluginName, getPluginId(pluginId), properties);
  }

  @Nullable
  @Override
  public <T> T usePlugin(String pluginType, String pluginName, String pluginId, PluginProperties properties,
                         PluginSelector selector) {
    return configurer.usePlugin(pluginType, pluginName, getPluginId(pluginId), properties, selector);
  }

  @Nullable
  @Override
  public <T> Class<T> usePluginClass(String pluginType, String pluginName, String pluginId,
                                     PluginProperties properties) {
    return configurer.usePluginClass(pluginType, pluginName, getPluginId(pluginId), properties);
  }

  @Nullable
  @Override
  public <T> Class<T> usePluginClass(String pluginType, String pluginName, String pluginId, PluginProperties properties,
                                     PluginSelector selector) {
    return configurer.usePluginClass(pluginType, pluginName, getPluginId(pluginId), properties, selector);
  }
  
  private String getPluginId(String childPluginId) {
    return String.format("%s%s%s", stageName, Constants.ID_SEPARATOR, childPluginId);
  }

  @Override
  public DefaultStageConfigurer getStageConfigurer() {
    return stageConfigurer;
  }

  @Override
  public Engine getEngine() {
    return engine;
  }

  @Override
  public void setPipelineProperties(Map<String, String> properties) {
    this.properties.clear();
    this.properties.putAll(properties);
  }

  @Override
  public MultiInputStageConfigurer getMultiInputStageConfigurer() {
    return stageConfigurer;
  }

  @Override
  public MultiOutputStageConfigurer getMultiOutputStageConfigurer() {
    return stageConfigurer;
  }

  /**
   * Returns pipeline properties
   * @return
   */
  public Map<String, String> getPipelineProperties() {
    return properties;
  }

  /**
   * Set pipeline properties belonging to all previous stages
   * @param propertiesFromStages All pipeline properties from previous stages as a Table object.)
   */
  public void setPropertiesFromStages(Table <String, String, String> propertiesFromStages) {
    LOG.warn("SETTING STAGE PROPERTIES");
    if(this.propertiesFromStages != null) {
      this.propertiesFromStages.clear();
    }
    this.propertiesFromStages = propertiesFromStages;
  }

  /**
   * Returns map of pipeline properties that were set in previous stage excluding the
   * properties set during the time of pipeline creation.
   * @param stageName Name of the stage.
   * @return
   */
  public Map<String, String> getPropertiesFromStages(String stageName) {
    LOG.warn("GETTING STAGE PROPERTIES");
    Map<String, String>  properties = new HashMap<>();
    propertiesFromStages.cellSet().forEach(cell -> {
      if(stageName.equals(cell.getValue())) {
        properties.put(cell.getRowKey(), cell.getColumnKey());
      }
    });
    return properties;
  }
}
