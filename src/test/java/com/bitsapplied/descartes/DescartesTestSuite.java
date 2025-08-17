package com.bitsapplied.descartes;

import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;

import com.bitsapplied.descartes.tools.ExceptionAnalysisToolTest;
import com.bitsapplied.descartes.tools.JShellSessionToolTest;
import com.bitsapplied.descartes.tools.JShellToolConcurrencyTest;
import com.bitsapplied.descartes.tools.JShellToolTest;
import com.bitsapplied.descartes.tools.LoggingIntegrationToolTest;
import com.bitsapplied.descartes.tools.MemoryAnalyzerToolTest;
import com.bitsapplied.descartes.tools.ObjectInspectorToolTest;
import com.bitsapplied.descartes.tools.ProcessInspectorToolTest;
import com.bitsapplied.descartes.tools.SystemMonitoringToolTest;
import com.bitsapplied.descartes.tools.ThreadAnalyzerToolTest;
import com.bitsapplied.descartes.util.ClassPathHelperTest;
import com.bitsapplied.descartes.util.ConsoleCaptureTest;
import com.bitsapplied.descartes.util.EvalResultTest;
import com.bitsapplied.descartes.util.InMemoryAppenderExceptionTest;
import com.bitsapplied.descartes.util.JShellInspectorTest;
import com.bitsapplied.descartes.util.JShellServiceTest;
import com.bitsapplied.descartes.util.JShellSessionManagerTest;
import com.bitsapplied.descartes.util.JShellSessionTest;
import com.bitsapplied.descartes.util.QueryParamsTest;
import com.bitsapplied.descartes.util.SessionEvalResultTest;

/**
 * Test suite for all Descartes MCP framework tests. This suite includes tests
 * for the generic MCP server components that have been extracted from Morpheus
 * into the Descartes package.
 */
@Suite
@SelectClasses({
    // Tool tests
    ExceptionAnalysisToolTest.class, JShellSessionToolTest.class, JShellToolConcurrencyTest.class, JShellToolTest.class,
    LoggingIntegrationToolTest.class, MemoryAnalyzerToolTest.class, ObjectInspectorToolTest.class,
    ProcessInspectorToolTest.class, SystemMonitoringToolTest.class, ThreadAnalyzerToolTest.class,

    // Utility tests
    ClassPathHelperTest.class, ConsoleCaptureTest.class, EvalResultTest.class, InMemoryAppenderExceptionTest.class,
    JShellInspectorTest.class, JShellServiceTest.class, JShellSessionManagerTest.class, JShellSessionTest.class,
    QueryParamsTest.class, SessionEvalResultTest.class })
public class DescartesTestSuite {
  // Test suite marker class
}