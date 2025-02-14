/*
* Licensed to the Apache Software Foundation (ASF) under one
* or more contributor license agreements.  See the NOTICE file
* distributed with this work for additional information
* regarding copyright ownership.  The ASF licenses this file
* to you under the Apache License, Version 2.0 (the
* "License"); you may not use this file except in compliance
* with the License.  You may obtain a copy of the License at
*
*   http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing,
* software distributed under the License is distributed on an
* "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
* KIND, either express or implied.  See the License for the
* specific language governing permissions and limitations
* under the License.
*/

package org.apache.samza.sql.translator;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.calcite.rel.core.Project;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.sql.validate.SqlUserDefinedFunction;
import org.apache.samza.SamzaException;
import org.apache.samza.context.ContainerContext;
import org.apache.samza.context.Context;
import org.apache.samza.metrics.Counter;
import org.apache.samza.metrics.MetricsRegistry;
import org.apache.samza.metrics.SamzaHistogram;
import org.apache.samza.operators.MessageStream;
import org.apache.samza.operators.functions.MapFunction;
import org.apache.samza.sql.data.Expression;
import org.apache.samza.sql.data.SamzaSqlRelMessage;
import org.apache.samza.sql.data.SamzaSqlRelMsgMetadata;
import org.apache.samza.sql.runner.SamzaSqlApplicationContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Translator to translate the Project node in the relational graph to the corresponding StreamGraph
 * implementation.
 */
class ProjectTranslator {

  private static final Logger LOG = LoggerFactory.getLogger(ProjectTranslator.class);
  //private transient int messageIndex = 0;
  private final int queryId;
  ProjectTranslator(int queryId) {
    this.queryId = queryId;
  }

  /**
   * ProjectMapFunction implements MapFunction to map input SamzaSqlRelMessages, one at a time, to a new
   * SamzaSqlRelMessage which consists of the projected fields
   */
  private static class ProjectMapFunction implements MapFunction<SamzaSqlRelMessage, SamzaSqlRelMessage> {
    private transient Project project;
    private transient Expression expr;
    private transient TranslatorContext translatorContext;
    private transient MetricsRegistry metricsRegistry;
    private transient SamzaHistogram processingTime; // milli-seconds
    private transient Counter inputEvents;
    private transient Counter outputEvents;

    private final int queryId;
    private final int projectId;
    private final String logicalOpId;
    private Context context;

    ProjectMapFunction(int projectId, int queryId, String logicalOpId) {
      this.projectId = projectId;
      this.queryId = queryId;
      this.logicalOpId = logicalOpId;
    }

    /**
     * initializes the ProjectMapFunction before any message is processed
     * @param context the {@link Context} for this task
     */
    @Override
    public void init(Context context) {
      this.context = context;
      this.translatorContext = ((SamzaSqlApplicationContext) context.getApplicationTaskContext()).getTranslatorContexts().get(queryId);
      this.project = (Project) this.translatorContext.getRelNode(projectId);
      this.expr = this.translatorContext.getExpressionCompiler().compile(project.getInputs(), project.getProjects());
      ContainerContext containerContext = context.getContainerContext();
      metricsRegistry = containerContext.getContainerMetricsRegistry();
      processingTime = new SamzaHistogram(metricsRegistry, logicalOpId, TranslatorConstants.PROCESSING_TIME_NAME);
      inputEvents = metricsRegistry.newCounter(logicalOpId, TranslatorConstants.INPUT_EVENTS_NAME);
      inputEvents.clear();
      outputEvents = metricsRegistry.newCounter(logicalOpId, TranslatorConstants.OUTPUT_EVENTS_NAME);
      outputEvents.clear();
    }

    /**
     * transforms the input message into the output message with projected fields
     * @param message  the input message to be transformed
     * @return the new SamzaSqlRelMessage message
     */
    @Override
    public SamzaSqlRelMessage apply(SamzaSqlRelMessage message) {
      Instant arrivalTime = Instant.now();
      RelDataType type = project.getRowType();
      Object[] output = new Object[type.getFieldCount()];
      expr.execute(translatorContext.getExecutionContext(), context, translatorContext.getDataContext(),
          message.getSamzaSqlRelRecord().getFieldValues().toArray(), output);
      List<String> names = new ArrayList<>();
      for (int index = 0; index < output.length; index++) {
        names.add(index, project.getNamedProjects().get(index).getValue());
      }
      updateMetrics(arrivalTime, Instant.now(), message.getSamzaSqlRelMsgMetadata().isNewInputMessage);
      return new SamzaSqlRelMessage(names, Arrays.asList(output), message.getSamzaSqlRelMsgMetadata());
    }

    /**
     * Updates the Diagnostics Metrics (processing time and number of events)
     * @param arrivalTime input message arrival time (= beging of processing in this operator)
     * @param outputTime output message output time (=end of processing in this operator)
     * @param isNewInputMessage whether the input Message is from new input message or not
     */
    private void updateMetrics(Instant arrivalTime, Instant outputTime, boolean isNewInputMessage) {
      if (isNewInputMessage) {
        inputEvents.inc();
      }
      outputEvents.inc();
      processingTime.update(Duration.between(arrivalTime, outputTime).toMillis());
    }

  }

  private MessageStream<SamzaSqlRelMessage> translateFlatten(Integer flattenIndex,
      MessageStream<SamzaSqlRelMessage> inputStream) {
    return inputStream.flatMap(message -> {
      Object field = message.getSamzaSqlRelRecord().getFieldValues().get(flattenIndex);
      if (field != null && field instanceof List) {
        List<SamzaSqlRelMessage> outMessages = new ArrayList<>();
        SamzaSqlRelMsgMetadata messageMetadata = message.getSamzaSqlRelMsgMetadata();
        SamzaSqlRelMsgMetadata newMetadata = new SamzaSqlRelMsgMetadata(messageMetadata.getEventTime(),
            messageMetadata.getarrivalTime(), messageMetadata.getscanTime(), true);
        for (Object fieldValue : (List) field) {
          List<Object> newValues = new ArrayList<>(message.getSamzaSqlRelRecord().getFieldValues());
          newValues.set(flattenIndex, Collections.singletonList(fieldValue));
          outMessages.add(new SamzaSqlRelMessage(message.getSamzaSqlRelRecord().getFieldNames(), newValues,
              newMetadata));
          newMetadata = new SamzaSqlRelMsgMetadata(newMetadata.getEventTime(), newMetadata.getarrivalTime(),
              newMetadata.getscanTime(), false);
        }
        return outMessages;
      } else {
        message.getSamzaSqlRelMsgMetadata().isNewInputMessage = true;
        return Collections.singletonList(message);
      }
    });
  }

  private boolean isFlatten(RexNode rexNode) {
    return rexNode instanceof RexCall && ((RexCall) rexNode).op instanceof SqlUserDefinedFunction
        && ((RexCall) rexNode).op.getName().equalsIgnoreCase("flatten");
  }

  private Integer getProjectIndex(RexNode rexNode) {
    return ((RexInputRef) ((RexCall) rexNode).getOperands().get(0)).getIndex();
  }

  void translate(final Project project, final String logicalOpId, final TranslatorContext context) {
    MessageStream<SamzaSqlRelMessage> messageStream = context.getMessageStream(project.getInput().getId());
    List<Integer> flattenProjects =
        project.getProjects().stream().filter(this::isFlatten).map(this::getProjectIndex).collect(Collectors.toList());

    if (flattenProjects.size() > 0) {
      if (flattenProjects.size() > 1) {
        String msg = "Multiple flatten operators in a single query is not supported";
        LOG.error(msg);
        throw new SamzaException(msg);
      }
      messageStream = translateFlatten(flattenProjects.get(0), messageStream);
    }

    final int projectId = project.getId();

    MessageStream<SamzaSqlRelMessage> outputStream = messageStream.map(new ProjectMapFunction(projectId, queryId, logicalOpId));

    context.registerMessageStream(project.getId(), outputStream);
    context.registerRelNode(project.getId(), project);
  }
}
