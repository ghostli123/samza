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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.calcite.DataContext;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.logical.LogicalProject;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.sql.validate.SqlUserDefinedFunction;
import org.apache.calcite.util.Pair;
import org.apache.samza.application.descriptors.StreamApplicationDescriptorImpl;
import org.apache.samza.context.ContainerContext;
import org.apache.samza.context.Context;
import org.apache.samza.operators.MessageStream;
import org.apache.samza.operators.MessageStreamImpl;
import org.apache.samza.operators.functions.MapFunction;
import org.apache.samza.operators.functions.ScheduledFunction;
import org.apache.samza.operators.functions.WatermarkFunction;
import org.apache.samza.operators.spec.OperatorSpec;
import org.apache.samza.operators.spec.StreamOperatorSpec;
import org.apache.samza.sql.data.Expression;
import org.apache.samza.sql.data.RexToJavaCompiler;
import org.apache.samza.sql.data.SamzaSqlExecutionContext;
import org.apache.samza.sql.data.SamzaSqlRelMessage;
import org.apache.samza.sql.data.SamzaSqlRelMsgMetadata;
import org.apache.samza.sql.runner.SamzaSqlApplicationContext;
import org.apache.samza.sql.util.TestMetricsRegistryImpl;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.internal.matchers.Any;
import org.mockito.internal.util.reflection.Whitebox;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;
import static org.junit.Assert.*;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


/**
 * Tests for {@link ProjectTranslator}
 */
@RunWith(PowerMockRunner.class)
@PrepareForTest(LogicalProject.class)
public class TestProjectTranslator extends TranslatorTestBase {
  final private String LOGICAL_OP_ID = "sql0_project_0";

  @Test
  public void testTranslate() throws IOException, ClassNotFoundException {
    // setup mock values to the constructor of FilterTranslator
    LogicalProject mockProject = PowerMockito.mock(LogicalProject.class);
    Context mockContext = mock(Context.class);
    ContainerContext mockContainerContext = mock(ContainerContext.class);
    TranslatorContext mockTranslatorContext = mock(TranslatorContext.class);
    TestMetricsRegistryImpl testMetricsRegistryImpl = new TestMetricsRegistryImpl();

    RelNode mockInput = mock(RelNode.class);
    List<RelNode> inputs = new ArrayList<>();
    inputs.add(mockInput);
    when(mockInput.getId()).thenReturn(1);
    when(mockProject.getId()).thenReturn(2);
    when(mockProject.getInputs()).thenReturn(inputs);
    when(mockProject.getInput()).thenReturn(mockInput);
    RelDataType mockRowType = mock(RelDataType.class);
    when(mockRowType.getFieldCount()).thenReturn(1);
    when(mockProject.getRowType()).thenReturn(mockRowType);
    RexNode mockRexField = mock(RexNode.class);
    List<Pair<RexNode, String>> namedProjects = new ArrayList<>();
    namedProjects.add(Pair.of(mockRexField, "test_field"));
    when(mockProject.getNamedProjects()).thenReturn(namedProjects);
    StreamApplicationDescriptorImpl mockAppDesc = mock(StreamApplicationDescriptorImpl.class);
    OperatorSpec<Object, SamzaSqlRelMessage> mockInputOp = mock(OperatorSpec.class);
    MessageStream<SamzaSqlRelMessage> mockStream = new MessageStreamImpl<>(mockAppDesc, mockInputOp);
    when(mockTranslatorContext.getMessageStream(eq(1))).thenReturn(mockStream);
    doAnswer(this.getRegisterMessageStreamAnswer()).when(mockTranslatorContext).registerMessageStream(eq(2), any(MessageStream.class));
    RexToJavaCompiler mockCompiler = mock(RexToJavaCompiler.class);
    when(mockTranslatorContext.getExpressionCompiler()).thenReturn(mockCompiler);
    Expression mockExpr = mock(Expression.class);
    when(mockCompiler.compile(any(), any())).thenReturn(mockExpr);
    when(mockContext.getContainerContext()).thenReturn(mockContainerContext);
    when(mockContainerContext.getContainerMetricsRegistry()).thenReturn(testMetricsRegistryImpl);

    // Apply translate() method to verify that we are getting the correct map operator constructed
    ProjectTranslator projectTranslator = new ProjectTranslator(1);
    projectTranslator.translate(mockProject, LOGICAL_OP_ID, mockTranslatorContext);
    // make sure that context has been registered with LogicFilter and output message streams
    verify(mockTranslatorContext, times(1)).registerRelNode(2, mockProject);
    verify(mockTranslatorContext, times(1)).registerMessageStream(2, this.getRegisteredMessageStream(2));
    when(mockTranslatorContext.getRelNode(2)).thenReturn(mockProject);
    when(mockTranslatorContext.getMessageStream(2)).thenReturn(this.getRegisteredMessageStream(2));
    StreamOperatorSpec projectSpec = (StreamOperatorSpec) Whitebox.getInternalState(this.getRegisteredMessageStream(2), "operatorSpec");
    assertNotNull(projectSpec);
    assertEquals(projectSpec.getOpCode(), OperatorSpec.OpCode.MAP);

    // Verify that the bootstrap() method will establish the context for the map function
    Map<Integer, TranslatorContext> mockContexts= new HashMap<>();
    mockContexts.put(1, mockTranslatorContext);
    when(mockContext.getApplicationTaskContext()).thenReturn(new SamzaSqlApplicationContext(mockContexts));
    projectSpec.getTransformFn().init(mockContext);
    MapFunction mapFn = (MapFunction) Whitebox.getInternalState(projectSpec, "mapFn");

    assertNotNull(mapFn);
    assertEquals(mockTranslatorContext, Whitebox.getInternalState(mapFn, "translatorContext"));
    assertEquals(mockProject, Whitebox.getInternalState(mapFn, "project"));
    assertEquals(mockExpr, Whitebox.getInternalState(mapFn, "expr"));
    // Verify TestMetricsRegistryImpl works with Project
    assertEquals(1, testMetricsRegistryImpl.getGauges().size());
    assertEquals(2, testMetricsRegistryImpl.getGauges().get(LOGICAL_OP_ID).size());
    assertEquals(1, testMetricsRegistryImpl.getCounters().size());
    assertEquals(2, testMetricsRegistryImpl.getCounters().get(LOGICAL_OP_ID).size());
    assertEquals(0, testMetricsRegistryImpl.getCounters().get(LOGICAL_OP_ID).get(0).getCount());
    assertEquals(0, testMetricsRegistryImpl.getCounters().get(LOGICAL_OP_ID).get(1).getCount());

    // Calling mapFn.apply() to verify the filter function is correctly applied to the input message
    SamzaSqlRelMessage mockInputMsg = new SamzaSqlRelMessage(new ArrayList<>(), new ArrayList<>(),
        new SamzaSqlRelMsgMetadata("", "", ""));
    SamzaSqlExecutionContext executionContext = mock(SamzaSqlExecutionContext.class);
    DataContext dataContext = mock(DataContext.class);
    when(mockTranslatorContext.getExecutionContext()).thenReturn(executionContext);
    when(mockTranslatorContext.getDataContext()).thenReturn(dataContext);
    Object[] result = new Object[1];
    final Object mockFieldObj = new Object();

    doAnswer( invocation -> {
      Object[] retValue = invocation.getArgumentAt(4, Object[].class);
      retValue[0] = mockFieldObj;
      return null;
    }).when(mockExpr).execute(eq(executionContext), eq(mockContext), eq(dataContext),
        eq(mockInputMsg.getSamzaSqlRelRecord().getFieldValues().toArray()), eq(result));
    SamzaSqlRelMessage retMsg = (SamzaSqlRelMessage) mapFn.apply(mockInputMsg);
    assertEquals(retMsg.getSamzaSqlRelRecord().getFieldNames(),
        new ArrayList<String>() {{
          this.add("test_field");
        }});
    assertEquals(retMsg.getSamzaSqlRelRecord().getFieldValues(), new ArrayList<Object>() {{
          this.add(mockFieldObj);
        }});

    // Verify mapFn.apply() updates the TestMetricsRegistryImpl metrics
    assertEquals(1, testMetricsRegistryImpl.getCounters().get(LOGICAL_OP_ID).get(0).getCount());
    assertEquals(1, testMetricsRegistryImpl.getCounters().get(LOGICAL_OP_ID).get(1).getCount());

  }


  @Test
  public void testTranslateWithFlatten() throws IOException, ClassNotFoundException {
    // setup mock values to the constructor of ProjectTranslator
    LogicalProject mockProject = PowerMockito.mock(LogicalProject.class);
    TranslatorContext mockTranslatorContext = mock(TranslatorContext.class);
    Context mockContext = mock(Context.class);
    ContainerContext mockContainerContext = mock(ContainerContext.class);
    TestMetricsRegistryImpl testMetricsRegistryImpl = new TestMetricsRegistryImpl();

    RelNode mockInput = mock(RelNode.class);
    List<RelNode> inputs = new ArrayList<>();
    inputs.add(mockInput);
    when(mockInput.getId()).thenReturn(1);
    when(mockProject.getId()).thenReturn(2);
    when(mockProject.getInputs()).thenReturn(inputs);
    when(mockProject.getInput()).thenReturn(mockInput);
    RelDataType mockRowType = mock(RelDataType.class);
    when(mockRowType.getFieldCount()).thenReturn(1);
    when(mockProject.getRowType()).thenReturn(mockRowType);
    RexNode mockRexField = mock(RexNode.class);
    List<Pair<RexNode, String>> namedProjects = new ArrayList<>();
    namedProjects.add(Pair.of(mockRexField, "test_field"));
    when(mockProject.getNamedProjects()).thenReturn(namedProjects);
    List<RexNode> flattenProjects = new ArrayList<>();
    RexCall mockFlattenProject = mock(RexCall.class);
    SqlUserDefinedFunction sqlFlattenUdf = mock(SqlUserDefinedFunction.class);
    when(sqlFlattenUdf.getName()).thenReturn("flatten");
    List<RexNode> flattenUdfOperands = new ArrayList<>();
    RexInputRef rexInputRef = mock(RexInputRef.class);
    when(rexInputRef.getIndex()).thenReturn(0);
    flattenUdfOperands.add(rexInputRef);
    when(mockFlattenProject.getOperands()).thenReturn(flattenUdfOperands);
    Whitebox.setInternalState(mockFlattenProject, "op", sqlFlattenUdf);
    flattenProjects.add(mockFlattenProject);
    when(mockProject.getProjects()).thenReturn(flattenProjects);

    StreamApplicationDescriptorImpl mockAppDesc = mock(StreamApplicationDescriptorImpl.class);
    OperatorSpec<Object, SamzaSqlRelMessage> mockInputOp = new OperatorSpec(OperatorSpec.OpCode.INPUT, "1") {

      @Override
      public WatermarkFunction getWatermarkFn() {
        return null;
      }

      @Override
      public ScheduledFunction getScheduledFn() {
        return null;
      }
    };

    MessageStream<SamzaSqlRelMessage> mockStream = new MessageStreamImpl<>(mockAppDesc, mockInputOp);
    when(mockTranslatorContext.getMessageStream(eq(1))).thenReturn(mockStream);
    doAnswer(this.getRegisterMessageStreamAnswer()).when(mockTranslatorContext).registerMessageStream(eq(2), any(MessageStream.class));
    RexToJavaCompiler mockCompiler = mock(RexToJavaCompiler.class);
    when(mockTranslatorContext.getExpressionCompiler()).thenReturn(mockCompiler);
    Expression mockExpr = mock(Expression.class);
    when(mockCompiler.compile(any(), any())).thenReturn(mockExpr);

    // Apply translate() method to verify that we are getting the correct map operator constructed
    ProjectTranslator projectTranslator = new ProjectTranslator(1);
    projectTranslator.translate(mockProject, LOGICAL_OP_ID, mockTranslatorContext);
    // make sure that context has been registered with LogicFilter and output message streams
    verify(mockTranslatorContext, times(1)).registerRelNode(2, mockProject);
    verify(mockTranslatorContext, times(1)).registerMessageStream(2, this.getRegisteredMessageStream(2));
    when(mockTranslatorContext.getRelNode(2)).thenReturn(mockProject);
    when(mockTranslatorContext.getMessageStream(2)).thenReturn(this.getRegisteredMessageStream(2));


    Collection<OperatorSpec>
        nextOps = ((OperatorSpec) Whitebox.getInternalState(mockStream, "operatorSpec")).getRegisteredOperatorSpecs();
    StreamOperatorSpec flattenOp = (StreamOperatorSpec) nextOps.iterator().next();
    assertNotNull(flattenOp);
    Object testObj = new Object();
    SamzaSqlRelMessage mockMsg = new SamzaSqlRelMessage(new ArrayList<String>() {{
      this.add("test_field_no1");
    }}, new ArrayList<Object>() {{
      this.add(testObj);
    }}, new SamzaSqlRelMsgMetadata("", "", ""));
    Collection<SamzaSqlRelMessage> flattenedMsgs = flattenOp.getTransformFn().apply(mockMsg);
    assertTrue(flattenedMsgs.size() == 1);
    assertTrue(flattenedMsgs.stream().anyMatch(s -> s.getSamzaSqlRelRecord().getFieldValues().get(0).equals(testObj)));
    List<Integer> testList = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      testList.add(new Integer(i));
    }
    mockMsg = new SamzaSqlRelMessage(new ArrayList<String>() {{
      this.add("test_list_field1");
    }}, new ArrayList<Object>() {{
      this.add(testList);
    }}, new SamzaSqlRelMsgMetadata("", "", ""));
    flattenedMsgs = flattenOp.getTransformFn().apply(mockMsg);
    assertTrue(flattenedMsgs.size() == 10);
    List<Integer> actualList = flattenedMsgs.stream()
        .map(m -> ((List<Integer>) m.getSamzaSqlRelRecord().getFieldValues().get(0)).get(0))
        .collect(ArrayList::new, (c, a) -> c.add(a), (c1, c2) -> c1.addAll(c2));
    assertEquals(testList, actualList);

    StreamOperatorSpec projectSpec = (StreamOperatorSpec) Whitebox.getInternalState(this.getRegisteredMessageStream(2), "operatorSpec");
    assertNotNull(projectSpec);
    assertEquals(projectSpec.getOpCode(), OperatorSpec.OpCode.MAP);

    // Verify that the describe() method will establish the context for the map function
    when(mockContext.getContainerContext()).thenReturn(mockContainerContext);
    when(mockContainerContext.getContainerMetricsRegistry()).thenReturn(testMetricsRegistryImpl);
    Map<Integer, TranslatorContext> mockContexts= new HashMap<>();
    mockContexts.put(1, mockTranslatorContext);
    when(mockContext.getApplicationTaskContext()).thenReturn(new SamzaSqlApplicationContext(mockContexts));
    projectSpec.getTransformFn().init(mockContext);
    MapFunction mapFn = (MapFunction) Whitebox.getInternalState(projectSpec, "mapFn");
    assertNotNull(mapFn);
    assertEquals(mockTranslatorContext, Whitebox.getInternalState(mapFn, "translatorContext"));
    assertEquals(mockProject, Whitebox.getInternalState(mapFn, "project"));
    assertEquals(mockExpr, Whitebox.getInternalState(mapFn, "expr"));
    // Verify TestMetricsRegistryImpl works with Project
    assertEquals(1, testMetricsRegistryImpl.getGauges().size());
    assertEquals(2, testMetricsRegistryImpl.getGauges().get(LOGICAL_OP_ID).size());
    assertEquals(1, testMetricsRegistryImpl.getCounters().size());
    assertEquals(2, testMetricsRegistryImpl.getCounters().get(LOGICAL_OP_ID).size());
    assertEquals(0, testMetricsRegistryImpl.getCounters().get(LOGICAL_OP_ID).get(0).getCount());
    assertEquals(0, testMetricsRegistryImpl.getCounters().get(LOGICAL_OP_ID).get(1).getCount());
    // Verify mapFn.apply() updates the TestMetricsRegistryImpl metrics
    for (SamzaSqlRelMessage message : flattenedMsgs) {
      mapFn.apply(message);
    }
    assertEquals(1, testMetricsRegistryImpl.getCounters().get(LOGICAL_OP_ID).get(0).getCount());
    assertEquals(10, testMetricsRegistryImpl.getCounters().get(LOGICAL_OP_ID).get(1).getCount());

    // Calling mapFn.apply() to verify the filter function is correctly applied to the input message
    SamzaSqlRelMessage mockInputMsg = new SamzaSqlRelMessage(new ArrayList<>(), new ArrayList<>(),
        new SamzaSqlRelMsgMetadata("", "", ""));
    SamzaSqlExecutionContext executionContext = mock(SamzaSqlExecutionContext.class);
    DataContext dataContext = mock(DataContext.class);
    when(mockTranslatorContext.getExecutionContext()).thenReturn(executionContext);
    when(mockTranslatorContext.getDataContext()).thenReturn(dataContext);
    Object[] result = new Object[1];
    final Object mockFieldObj = new Object();

    doAnswer( invocation -> {
      Object[] retValue = invocation.getArgumentAt(4, Object[].class);
      retValue[0] = mockFieldObj;
      return null;
    }).when(mockExpr).execute(eq(executionContext), eq(mockContext), eq(dataContext),
        eq(mockInputMsg.getSamzaSqlRelRecord().getFieldValues().toArray()), eq(result));
    SamzaSqlRelMessage retMsg = (SamzaSqlRelMessage) mapFn.apply(mockInputMsg);
    assertEquals(retMsg.getSamzaSqlRelRecord().getFieldNames(),
        new ArrayList<String>() {{
          this.add("test_field");
        }});
    assertEquals(retMsg.getSamzaSqlRelRecord().getFieldValues(), new ArrayList<Object>() {{
      this.add(mockFieldObj);
    }});

  }
}
