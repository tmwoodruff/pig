/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.pig.backend.hadoop.executionengine.spark.running;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.JobContext;
import org.apache.hadoop.mapreduce.JobID;
import org.apache.hadoop.mapreduce.RecordReader;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.hadoop.mapreduce.lib.input.FileSplit;
import org.apache.pig.backend.hadoop.executionengine.mapReduceLayer.PigHadoopLogger;
import org.apache.pig.backend.hadoop.executionengine.mapReduceLayer.PigInputFormat;
import org.apache.pig.backend.hadoop.executionengine.mapReduceLayer.PigMapReduce;
import org.apache.pig.backend.hadoop.executionengine.mapReduceLayer.PigSplit;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.PhysicalOperator;
import org.apache.pig.backend.hadoop.executionengine.shims.HadoopShims;
import org.apache.pig.backend.hadoop.executionengine.spark.SparkPigRecordReader;
import org.apache.pig.backend.hadoop.executionengine.spark.SparkPigSplit;
import org.apache.pig.backend.hadoop.executionengine.util.MapRedUtil;
import org.apache.pig.data.SchemaTupleBackend;
import org.apache.pig.data.Tuple;
import org.apache.pig.impl.PigContext;
import org.apache.pig.impl.PigImplConstants;
import org.apache.pig.impl.util.ObjectSerializer;
import org.apache.pig.impl.util.UDFContext;
import org.apache.pig.tools.pigstats.PigStatusReporter;
import org.apache.pig.tools.pigstats.spark.SparkCounters;
import org.apache.pig.tools.pigstats.spark.SparkTaskContext;

public class PigInputFormatSpark extends PigInputFormat {

    @Override
    public RecordReader<Text, Tuple> createRecordReader(InputSplit split, TaskAttemptContext context) throws
            IOException, InterruptedException {
        resetUDFContext();
        //PigSplit#conf is the default hadoop configuration, we need get the configuration
        //from context.getConfigration() to retrieve pig properties
        PigSplit pigSplit = ((SparkPigSplit) split).getWrappedPigSplit();
        Configuration conf = context.getConfiguration();
        pigSplit.setConf(conf);
        //Set current splitIndex in PigMapReduce.sJobContext.getConfiguration.get(PigImplConstants.PIG_SPLIT_INDEX)
        //which will be used in POMergeCogroup#setup
        if (PigMapReduce.sJobContext == null) {
            PigMapReduce.sJobContext = HadoopShims.createJobContext(conf, new JobID());
        }
        PigMapReduce.sJobContext.getConfiguration().setInt(PigImplConstants.PIG_SPLIT_INDEX, pigSplit.getSplitIndex());
        // Here JobConf is first available in spark Executor thread, we initialize PigContext,UDFContext and
        // SchemaTupleBackend by reading properties from JobConf
        initialize(conf);

        SparkRecordReaderFactory sparkRecordReaderFactory = new SparkRecordReaderFactory(pigSplit, context);
        return sparkRecordReaderFactory.createRecordReader();
    }

    /**
     * This is where we have to wrap PigSplits into SparkPigSplits
     * @param jobcontext
     * @return
     * @throws IOException
     * @throws InterruptedException
     */
    @Override
    public List<InputSplit> getSplits(JobContext jobcontext) throws IOException, InterruptedException {
        
        // Track whether UDF conf is empty when we start. If it is, we clear out ThreadLocal context set by 
        // super.getSplits() to ensure that it is still empty when we return. This is needed because getSplits() 
        // is called from the dag-scheduler-event-loop thread, and SparkEngineConf.writeObject() assumes it won't exist
        // in that thread.
        boolean udfConfEmpty = UDFContext.getUDFContext().isUDFConfEmpty();
        
        List<InputSplit> sparkPigSplits = new ArrayList<>();
        List<InputSplit> originalSplits;
        
        try {
            originalSplits = super.getSplits(jobcontext);
        } finally {
            if (udfConfEmpty) {
                PigContext.setPackageImportList(null);
                UDFContext.getUDFContext().addJobConf(null);
                UDFContext.getUDFContext().reset();
            }
        }

        boolean isFileSplits = true;
        for (InputSplit inputSplit : originalSplits) {
            PigSplit split = (PigSplit)inputSplit;
            if (!(split.getWrappedSplit() instanceof FileSplit)) {
                isFileSplits = false;
                break;
            }
        }

        for (InputSplit inputSplit : originalSplits) {
            PigSplit split = (PigSplit) inputSplit;
            if (!isFileSplits) {
                sparkPigSplits.add(new SparkPigSplit.GenericSparkPigSplit(split));
            } else {
                sparkPigSplits.add(new SparkPigSplit.FileSparkPigSplit(split));
            }
        }

        return sparkPigSplits;
    }

    private void initialize(Configuration jobConf) throws IOException {
        MapRedUtil.setupUDFContext(jobConf);
        PigContext pc = (PigContext) ObjectSerializer.deserialize(jobConf.get("pig.pigContext"));
        SchemaTupleBackend.initialize(jobConf, pc);
        PigMapReduce.sJobConfInternal.set(jobConf);
        SparkCounters counters = (SparkCounters)ObjectSerializer.deserialize(jobConf.get("pig.spark.counters"));
        PigStatusReporter reporter = PigStatusReporter.getInstance();
        reporter.setContext(new SparkTaskContext(counters));
        PigHadoopLogger pigHadoopLogger = PigHadoopLogger.getInstance();
        pigHadoopLogger.setAggregate("true".equalsIgnoreCase(jobConf.get("aggregate.warning")));
        pigHadoopLogger.setReporter(counters); // Using counters as reporter here for custom warn handling
        PhysicalOperator.setPigLogger(pigHadoopLogger);
    }

    private void resetUDFContext() {
        UDFContext.getUDFContext().reset();
    }


    static class SparkRecordReaderFactory extends PigInputFormat.RecordReaderFactory {

        public SparkRecordReaderFactory(InputSplit split, TaskAttemptContext context) throws IOException {
            super(split, context);
        }

        @Override
        public RecordReader<Text, Tuple> createRecordReader() throws IOException, InterruptedException {
            return new SparkPigRecordReader(inputFormat, pigSplit, decorator, context, limit);
        }
    }
}