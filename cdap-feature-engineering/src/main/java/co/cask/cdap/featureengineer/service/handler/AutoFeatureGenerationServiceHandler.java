/*
 * Copyright © 2018 Cask Data, Inc.
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
package co.cask.cdap.featureengineer.service.handler;

import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.QueryParam;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import co.cask.cdap.api.annotation.Property;
import co.cask.cdap.api.dataset.lib.KeyValueTable;
import co.cask.cdap.api.service.http.HttpServiceContext;
import co.cask.cdap.api.service.http.HttpServiceRequest;
import co.cask.cdap.api.service.http.HttpServiceResponder;
import co.cask.cdap.featureengineer.AutoFeatureGenerator;
import co.cask.cdap.featureengineer.AutoFeatureGenerator.AutoFeatureGeneratorResult;
import co.cask.cdap.featureengineer.FeatureEngineeringApp.FeatureEngineeringConfig;
import co.cask.cdap.featureengineer.RequestExtractor;
import co.cask.cdap.featureengineer.enums.FeatureGenerationConfigParams;
import co.cask.cdap.featureengineer.enums.PipelineType;
import co.cask.cdap.featureengineer.pipeline.pojo.CDAPPipelineInfo;
import co.cask.cdap.featureengineer.pipeline.pojo.NullableSchema;
import co.cask.cdap.featureengineer.proto.FeatureGenerationRequest;
import co.cask.cdap.featureengineer.request.pojo.DataSchemaNameList;
import co.cask.cdap.featureengineer.response.pojo.FeatureGenerationConfigParam;
import co.cask.cdap.featureengineer.response.pojo.FeatureGenerationConfigParamList;
import co.cask.cdap.featureengineer.utils.JSONInputParser;

/**
 * @author bhupesh.goel
 *
 */
public class AutoFeatureGenerationServiceHandler extends BaseServiceHandler {

	private static final Logger LOG = LoggerFactory.getLogger(AutoFeatureGenerationServiceHandler.class);

	@Property
	private final String dataSchemaTableName;
	@Property
	private final String pluginConfigTableName;
	@Property
	private final String featureDAGTableName;
	@Property
	private final String featureEngineeringConfigTableName;
	@Property
	private final String pipelineDataSchemasTableName;
	@Property
	private final String pipelineNameTableName;
	
	private KeyValueTable dataSchemaTable;
	private KeyValueTable pluginConfigTable;
	private KeyValueTable featureDAGTable;
	private KeyValueTable featureEngineeringConfigTable;
	private KeyValueTable pipelineDataSchemasTable;
	private KeyValueTable pipelineNameTable;
	
	private HttpServiceContext context;
	
	/**
	 * @param config
	 * 
	 */
	public AutoFeatureGenerationServiceHandler(FeatureEngineeringConfig config) {
		this.dataSchemaTableName = config.getDataSchemaTable();
		this.pluginConfigTableName = config.getPluginConfigTable();
		this.featureDAGTableName = config.getFeatureDAGTable();
		this.featureEngineeringConfigTableName = config.getFeatureEngineeringConfigTable();
		this.pipelineDataSchemasTableName = config.getPipelineDataSchemasTable();
		this.pipelineNameTableName = config.getPipelineNameTable();
	}

	@Override
	public void initialize(HttpServiceContext context) throws Exception {
		super.initialize(context);
		this.dataSchemaTable = context.getDataset(dataSchemaTableName);
		this.pluginConfigTable = context.getDataset(pluginConfigTableName);
		this.featureDAGTable = context.getDataset(featureDAGTableName);
		this.featureEngineeringConfigTable = context.getDataset(featureEngineeringConfigTableName);
		this.pipelineDataSchemasTable = context.getDataset(pipelineDataSchemasTableName);
		this.pipelineNameTable = context.getDataset(pipelineNameTableName);
		this.context = context;
	}

	@POST
	@Path("featureengineering/{pipelineName}/features/create")
	public void generateFeatures(HttpServiceRequest request, HttpServiceResponder responder, @PathParam("pipelineName") String pipelineName) {
		Map<String, NullableSchema> inputDataschemaMap = null;
		try {
			FeatureGenerationRequest featureGenerationRequest = new RequestExtractor(request).getContent("UTF-8",
					FeatureGenerationRequest.class);
			featureGenerationRequest.setPipelineRunName(pipelineName);
			inputDataschemaMap = getSchemaMap(featureGenerationRequest.getDataSchemaNames(), dataSchemaTable);
			Map<String, CDAPPipelineInfo> wranglerPluginConfigMap = getWranglerPluginConfigMap(
					featureGenerationRequest.getDataSchemaNames(), pluginConfigTable);
			String hostAndPort[] = getHostAndPort(request);
			AutoFeatureGeneratorResult result = new AutoFeatureGenerator(featureGenerationRequest, inputDataschemaMap,
					wranglerPluginConfigMap).generateFeatures(hostAndPort);
			featureDAGTable.write(result.getPipelineName(), result.getFeatureEngineeringDAG());
			featureEngineeringConfigTable.write(result.getPipelineName(),
					JSONInputParser.convertToJSON(featureGenerationRequest));
			List<String> dataSchemaNames = new LinkedList<String>(inputDataschemaMap.keySet());
			DataSchemaNameList schemaNameList = new DataSchemaNameList();
			schemaNameList.setDataSchemaName(dataSchemaNames);
			pipelineDataSchemasTable.write(result.getPipelineName(), JSONInputParser.convertToJSON(schemaNameList));
			pipelineNameTable.write(result.getPipelineName(), PipelineType.FEATURE_GENERATION.getName());
			success(responder, "Successfully Generated Features for data schemas " + inputDataschemaMap.keySet()
					+ " with pipeline name = " + result.getPipelineName());
		} catch (Exception e) {
			error(responder, "Failed to generate features for data schemas " + inputDataschemaMap.keySet()
					+ " with error message " + e.getMessage());
		}
	}
	
	@GET
	@Path("featureengineering/feature/generation/configparams/get")
	public void getFeatureGenerationConfigParameters(HttpServiceRequest request, HttpServiceResponder responder,
			@QueryParam("getSchemaParams") Boolean getSchemaParams) {
		
		FeatureGenerationConfigParamList configParamList = new FeatureGenerationConfigParamList();
		for(FeatureGenerationConfigParams configParam : FeatureGenerationConfigParams.values()) {
			if(getSchemaParams.equals(configParam.isSchemaSpecific())) {
				configParamList.addConfigParam(new FeatureGenerationConfigParam(configParam.getName(), configParam.getDescription()));
			}
		}
		responder.sendJson(configParamList);
	}
}