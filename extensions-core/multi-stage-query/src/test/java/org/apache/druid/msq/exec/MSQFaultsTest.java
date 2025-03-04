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

package org.apache.druid.msq.exec;

import com.google.common.collect.ImmutableMap;
import org.apache.druid.error.DruidException;
import org.apache.druid.error.DruidExceptionMatcher;
import org.apache.druid.indexing.common.actions.SegmentAllocateAction;
import org.apache.druid.java.util.common.Intervals;
import org.apache.druid.java.util.common.StringUtils;
import org.apache.druid.msq.indexing.error.InsertCannotAllocateSegmentFault;
import org.apache.druid.msq.indexing.error.InsertCannotBeEmptyFault;
import org.apache.druid.msq.indexing.error.InsertTimeNullFault;
import org.apache.druid.msq.indexing.error.InsertTimeOutOfBoundsFault;
import org.apache.druid.msq.indexing.error.TooManyClusteredByColumnsFault;
import org.apache.druid.msq.indexing.error.TooManyColumnsFault;
import org.apache.druid.msq.indexing.error.TooManyInputFilesFault;
import org.apache.druid.msq.indexing.error.TooManyPartitionsFault;
import org.apache.druid.msq.test.MSQTestBase;
import org.apache.druid.msq.test.MSQTestFileUtils;
import org.apache.druid.segment.column.ColumnType;
import org.apache.druid.segment.column.RowSignature;
import org.apache.druid.segment.realtime.appenderator.SegmentIdWithShardSpec;
import org.apache.druid.timeline.partition.LinearShardSpec;
import org.junit.Test;
import org.mockito.Mockito;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.mockito.ArgumentMatchers.isA;

public class MSQFaultsTest extends MSQTestBase
{
  @Test
  public void testInsertCannotAllocateSegmentFaultWhenNullAllocation()
  {
    RowSignature rowSignature = RowSignature.builder()
                                            .add("__time", ColumnType.LONG)
                                            .add("dim1", ColumnType.STRING)
                                            .add("cnt", ColumnType.LONG).build();

    // If there is some problem allocating the segment,task action client will return a null value.
    Mockito.doReturn(null).when(testTaskActionClient).submit(isA(SegmentAllocateAction.class));

    testIngestQuery().setSql(
                         "insert into foo1"
                         + " select  __time, dim1 , count(*) as cnt"
                         + " from foo"
                         + " where dim1 is not null and __time >= TIMESTAMP '2000-01-02 00:00:00' and __time < TIMESTAMP '2000-01-03 00:00:00'"
                         + " group by 1, 2"
                         + " PARTITIONED by day"
                         + " clustered by dim1"
                     )
                     .setExpectedDataSource("foo1")
                     .setExpectedRowSignature(rowSignature)
                     .setExpectedMSQFault(
                         new InsertCannotAllocateSegmentFault(
                             "foo1",
                             Intervals.of("2000-01-02T00:00:00.000Z/2000-01-03T00:00:00.000Z"),
                             null
                         )
                     )
                     .verifyResults();
  }

  @Test
  public void testInsertCannotAllocateSegmentFaultWhenInvalidAllocation()
  {
    RowSignature rowSignature = RowSignature.builder()
                                            .add("__time", ColumnType.LONG)
                                            .add("dim1", ColumnType.STRING)
                                            .add("cnt", ColumnType.LONG).build();

    // If there is some problem allocating the segment,task action client will return a null value.
    Mockito.doReturn(new SegmentIdWithShardSpec(
        "foo1",
        Intervals.of("2000-01-01/2000-02-01"),
        "test",
        new LinearShardSpec(2)
    )).when(testTaskActionClient).submit(isA(SegmentAllocateAction.class));

    testIngestQuery().setSql(
                         "insert into foo1"
                         + " select  __time, dim1 , count(*) as cnt"
                         + " from foo"
                         + " where dim1 is not null and __time >= TIMESTAMP '2000-01-02 00:00:00' and __time < TIMESTAMP '2000-01-03 00:00:00'"
                         + " group by 1, 2"
                         + " PARTITIONED by day"
                         + " clustered by dim1"
                     )
                     .setExpectedDataSource("foo1")
                     .setExpectedRowSignature(rowSignature)
                     .setExpectedMSQFault(
                         new InsertCannotAllocateSegmentFault(
                             "foo1",
                             Intervals.of("2000-01-02T00:00:00.000Z/2000-01-03T00:00:00.000Z"),
                             Intervals.of("2000-01-01T00:00:00.000Z/2000-02-01T00:00:00.000Z")
                         )
                     )
                     .verifyResults();
  }


  @Test
  public void testInsertCannotBeEmptyFault()
  {
    RowSignature rowSignature = RowSignature.builder()
                                            .add("__time", ColumnType.LONG)
                                            .add("dim1", ColumnType.STRING)
                                            .add("cnt", ColumnType.LONG).build();

    //Insert with a condition which results in 0 rows being inserted
    testIngestQuery().setSql(
                         "insert into foo1 select  __time, dim1 , count(*) as cnt from foo where dim1 is not null and __time < TIMESTAMP '1971-01-01 00:00:00' group by 1, 2 PARTITIONED by day clustered by dim1")
                     .setExpectedDataSource("foo1")
                     .setExpectedRowSignature(rowSignature)
                     .setExpectedMSQFault(new InsertCannotBeEmptyFault("foo1"))
                     .verifyResults();
  }

  @Test
  public void testInsertTimeOutOfBoundsFault()
  {
    RowSignature rowSignature = RowSignature.builder()
                                            .add("__time", ColumnType.LONG)
                                            .add("dim1", ColumnType.STRING)
                                            .add("cnt", ColumnType.LONG).build();

    // Add a REPLACE statement which replaces a different partition than the ones which rows are present for. The generated
    // partition will be outside the replace interval which should throw an InsertTimeOutOfBoundsFault.
    testIngestQuery().setSql(
                         "replace into foo1 overwrite where __time >= TIMESTAMP '2002-01-02 00:00:00' and __time < TIMESTAMP '2002-01-03 00:00:00' select  __time, dim1 , count(*) as cnt from foo where dim1 is not null group by 1, 2 PARTITIONED by day clustered by dim1")
                     .setExpectedDataSource("foo1")
                     .setExpectedRowSignature(rowSignature)
                     .setExpectedMSQFault(
                         new InsertTimeOutOfBoundsFault(
                             Intervals.of("2000-01-02T00:00:00.000Z/2000-01-03T00:00:00.000Z"),
                             Collections.singletonList(Intervals.of("2002-01-02/2002-01-03"))
                         )
                     )
                     .verifyResults();
  }

  @Test
  public void testInsertTimeNullFault()
  {
    final String expectedDataSource = "foo1";

    final RowSignature rowSignature =
        RowSignature.builder()
                    .add("__time", ColumnType.LONG)
                    .add("cnt", ColumnType.STRING)
                    .build();

    final String sql = "INSERT INTO foo1\n"
                     + "SELECT TIME_PARSE(dim1) AS __time, dim1 as cnt\n"
                     + "FROM foo\n"
                     + "PARTITIONED BY DAY\n"
                     + "CLUSTERED BY dim1";

    testIngestQuery()
        .setSql(sql)
        .setExpectedDataSource(expectedDataSource)
        .setExpectedRowSignature(rowSignature)
        .setExpectedMSQFault(InsertTimeNullFault.instance())
        .verifyResults();
  }

  @Test
  public void testInsertWithTooManySegments() throws IOException
  {
    Map<String, Object> context = ImmutableMap.<String, Object>builder()
                                              .putAll(DEFAULT_MSQ_CONTEXT)
                                              .put("rowsPerSegment", 1)
                                              .build();


    RowSignature rowSignature = RowSignature.builder()
                                            .add("__time", ColumnType.LONG)
                                            .build();

    File file = MSQTestFileUtils.generateTemporaryNdJsonFile(temporaryFolder, 30000, 1);
    String filePathAsJson = queryFramework().queryJsonMapper().writeValueAsString(file.getAbsolutePath());

    testIngestQuery().setSql(" insert into foo1 SELECT\n"
                             + "  floor(TIME_PARSE(\"timestamp\") to day) AS __time\n"
                             + "FROM TABLE(\n"
                             + "  EXTERN(\n"
                             + "    '{ \"files\": [" + filePathAsJson + "],\"type\":\"local\"}',\n"
                             + "    '{\"type\": \"json\"}',\n"
                             + "    '[{\"name\": \"timestamp\",\"type\":\"string\"}]'\n"
                             + "  )\n"
                             + ") PARTITIONED by day")
                     .setExpectedDataSource("foo1")
                     .setExpectedRowSignature(rowSignature)
                     .setQueryContext(context)
                     .setExpectedMSQFault(new TooManyPartitionsFault(25000))
                     .verifyResults();

  }

  @Test
  public void testInsertWithManyColumns()
  {
    RowSignature dummyRowSignature = RowSignature.builder().add("__time", ColumnType.LONG).build();

    final int numColumns = 2000;

    String columnNames = IntStream.range(1, numColumns)
                                  .mapToObj(i -> "col" + i).collect(Collectors.joining(", "));

    String externSignature = IntStream.range(1, numColumns)
                                      .mapToObj(i -> StringUtils.format(
                                          "{\"name\": \"col%d\", \"type\": \"string\"}",
                                          i
                                      ))
                                      .collect(Collectors.joining(", "));

    testIngestQuery()
        .setSql(StringUtils.format(
            " insert into foo1 SELECT\n"
            + "  floor(TIME_PARSE(\"timestamp\") to day) AS __time,\n"
            + " %s\n"
            + "FROM TABLE(\n"
            + "  EXTERN(\n"
            + "    '{ \"files\": [\"ignored\"],\"type\":\"local\"}',\n"
            + "    '{\"type\": \"json\"}',\n"
            + "    '[{\"name\": \"timestamp\", \"type\": \"string\"}, %s]'\n"
            + "  )\n"
            + ") PARTITIONED by day",
            columnNames,
            externSignature
        ))
        .setExpectedDataSource("foo1")
        .setExpectedRowSignature(dummyRowSignature)
        .setExpectedMSQFault(new TooManyColumnsFault(numColumns + 2, 2000))
        .verifyResults();
  }

  @Test
  public void testInsertWithHugeClusteringKeys()
  {
    RowSignature dummyRowSignature = RowSignature.builder().add("__time", ColumnType.LONG).build();

    final int numColumns = 1700;

    String columnNames = IntStream.range(1, numColumns)
                                  .mapToObj(i -> "col" + i).collect(Collectors.joining(", "));

    String clusteredByClause = IntStream.range(1, numColumns + 1)
                                        .mapToObj(String::valueOf)
                                        .collect(Collectors.joining(", "));

    String externSignature = IntStream.range(1, numColumns)
                                      .mapToObj(i -> StringUtils.format(
                                          "{\"name\": \"col%d\", \"type\": \"string\"}",
                                          i
                                      ))
                                      .collect(Collectors.joining(", "));

    testIngestQuery()
        .setSql(StringUtils.format(
            " insert into foo1 SELECT\n"
            + "  floor(TIME_PARSE(\"timestamp\") to day) AS __time,\n"
            + " %s\n"
            + "FROM TABLE(\n"
            + "  EXTERN(\n"
            + "    '{ \"files\": [\"ignored\"],\"type\":\"local\"}',\n"
            + "    '{\"type\": \"json\"}',\n"
            + "    '[{\"name\": \"timestamp\", \"type\": \"string\"}, %s]'\n"
            + "  )\n"
            + ") PARTITIONED by day CLUSTERED BY %s",
            columnNames,
            externSignature,
            clusteredByClause
        ))
        .setExpectedDataSource("foo1")
        .setExpectedRowSignature(dummyRowSignature)
        .setExpectedMSQFault(new TooManyClusteredByColumnsFault(numColumns + 2, 1500, 0))
        .verifyResults();
  }

  @Test
  public void testTooManyInputFiles() throws IOException
  {
    RowSignature dummyRowSignature = RowSignature.builder().add("__time", ColumnType.LONG).build();

    final int numFiles = 20000;

    final File toRead = MSQTestFileUtils.getResourceAsTemporaryFile(temporaryFolder, this, "/wikipedia-sampled.json");
    final String toReadFileNameAsJson = queryFramework().queryJsonMapper().writeValueAsString(toRead.getAbsolutePath());

    String externalFiles = String.join(", ", Collections.nCopies(numFiles, toReadFileNameAsJson));

    testIngestQuery()
        .setSql(StringUtils.format(
            "insert into foo1 SELECT\n"
            + "  floor(TIME_PARSE(\"timestamp\") to day) AS __time\n"
            + "FROM TABLE(\n"
            + "  EXTERN(\n"
            + "    '{ \"files\": [%s],\"type\":\"local\"}',\n"
            + "    '{\"type\": \"csv\", \"hasHeaderRow\": true}',\n"
            + "    '[{\"name\": \"timestamp\", \"type\": \"string\"}]'\n"
            + "  )\n"
            + ") PARTITIONED by day",
            externalFiles
        ))
        .setExpectedDataSource("foo1")
        .setExpectedRowSignature(dummyRowSignature)
        .setExpectedMSQFault(new TooManyInputFilesFault(numFiles, Limits.MAX_INPUT_FILES_PER_WORKER, 2))
        .verifyResults();
  }

  @Test
  public void testUnionAllWithDifferentColumnNames()
  {
    // This test fails till MSQ can support arbitrary column names and column types for UNION ALL
    testIngestQuery()
        .setSql(
            "INSERT INTO druid.dst "
            + "SELECT dim2, dim1, m1 FROM foo2 "
            + "UNION ALL "
            + "SELECT dim1, dim2, m1 FROM foo "
            + "PARTITIONED BY ALL TIME")
        .setExpectedValidationErrorMatcher(
            new DruidExceptionMatcher(
                DruidException.Persona.ADMIN,
                DruidException.Category.INVALID_INPUT,
                "general"
            ).expectMessageContains("SQL requires union between two tables and column names queried for each table are different "
                              + "Left: [dim2, dim1, m1], Right: [dim1, dim2, m1]."))
        .verifyPlanningErrors();
  }

  @Test
  public void testTopLevelUnionAllWithJoins()
  {
    // This test fails becaues it is a top level UNION ALL which cannot be planned using MSQ. It will be supported once
    // we support arbitrary types and column names for UNION ALL
    testSelectQuery()
        .setSql(
            "(SELECT COUNT(*) FROM foo INNER JOIN lookup.lookyloo ON foo.dim1 = lookyloo.k) "
            + "UNION ALL "
            + "(SELECT SUM(cnt) FROM foo)"
        )
        .setExpectedValidationErrorMatcher(
            new DruidExceptionMatcher(
                DruidException.Persona.ADMIN,
                DruidException.Category.INVALID_INPUT,
                "general"
            ).expectMessageContains(
                "SQL requires union between inputs that are not simple table scans and involve a filter or aliasing"))
        .verifyPlanningErrors();
  }
}
