/*
 * Copyright (c) 2019 Snowflake Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.snowflake.kafka.connector;

import com.snowflake.kafka.connector.internal.Logging;
import com.snowflake.kafka.connector.internal.SnowflakeConnectionService;
import com.snowflake.kafka.connector.internal.SnowflakeConnectionServiceFactory;
import com.snowflake.kafka.connector.internal.SnowflakeErrors;
import com.snowflake.kafka.connector.internal.SnowflakeSinkService;
import com.snowflake.kafka.connector.internal.SnowflakeSinkServiceFactory;
import com.snowflake.kafka.connector.records.SnowflakeMetadataConfig;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.connect.errors.RetriableException;
import org.apache.kafka.connect.sink.SinkRecord;
import org.apache.kafka.connect.sink.SinkTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * SnowflakeSinkTask implements SinkTask for Kafka Connect framework.
 * expects configuration from SnowflakeSinkConnector
 * creates sink service instance
 * takes records loaded from those Kafka partitions,
 * ingests to Snowflake via Sink service
 */
public class SnowflakeSinkTask extends SinkTask
{
  private static final long WAIT_TIME = 5 * 1000;//5 sec
  private static final int REPEAT_TIME = 12; //60 sec

  private SnowflakeSinkService sink = null;
  private Map<String, String> topic2table = null;

  // snowflake JDBC connection provides methods to interact with user's
  // snowflake
  // account and execute queries
  private SnowflakeConnectionService conn = null;
  private String id = "-1";

  private static final Logger LOGGER = LoggerFactory
    .getLogger(SnowflakeSinkTask.class);

  /**
   * default constructor, invoked by kafka connect framework
   */
  public SnowflakeSinkTask()
  {
    //nothing
  }

  private SnowflakeConnectionService getConnection()
  {
    try
    {
      waitFor(() -> conn != null);
    } catch (Exception e)
    {
      throw SnowflakeErrors.ERROR_5013.getException();
    }
    return conn;
  }

  private SnowflakeSinkService getSink()
  {
    try
    {
      waitFor(() -> sink != null && !sink.isClosed());
    } catch (Exception e)
    {
      throw SnowflakeErrors.ERROR_5014.getException();
    }
    return sink;
  }


  /**
   * start method handles configuration parsing and one-time setup of the
   * task. loads configuration
   *
   * @param parsedConfig - has the configuration settings
   */
  @Override
  public void start(final Map<String, String> parsedConfig)
  {
    this.id = parsedConfig.getOrDefault(Utils.TASK_ID, "-1");

    LOGGER.info(Logging.logMessage("SnowflakeSinkTask[ID:{}]:start", this.id));
    // connector configuration

    //generate topic to table map
    this.topic2table = getTopicToTableMap(parsedConfig);

    // generate metadataConfig table
    SnowflakeMetadataConfig metadataConfig = new SnowflakeMetadataConfig(parsedConfig);

    //enable jvm proxy
    Utils.enableJVMProxy(parsedConfig);

    // config buffer.count.records -- how many records to buffer
    final long bufferCountRecords = Long.parseLong(parsedConfig.get
      (SnowflakeSinkConnectorConfig.BUFFER_COUNT_RECORDS));
    // config buffer.size.bytes -- aggregate size in bytes of all records to
    // buffer
    final long bufferSizeBytes = Long.parseLong(parsedConfig.get
      (SnowflakeSinkConnectorConfig.BUFFER_SIZE_BYTES));
    final long bufferFlushTime = Long.parseLong(parsedConfig.get
      (SnowflakeSinkConnectorConfig.BUFFER_FLUSH_TIME_SEC));

    conn = SnowflakeConnectionServiceFactory
      .builder()
      .setProperties(parsedConfig)
      .build();

    if (this.sink != null)
    {
      this.sink.closeAll();
    }
    this.sink = SnowflakeSinkServiceFactory.builder(getConnection())
      .setFileSize(bufferSizeBytes)
      .setRecordNumber(bufferCountRecords)
      .setFlushTime(bufferFlushTime)
      .setTopic2TableMap(topic2table)
      .setMetadataConfig(metadataConfig)
      .build();
  }

  /**
   * stop method is invoked only once outstanding calls to other methods
   * have completed.
   * e.g. after current put, and a final preCommit has completed.
   */
  @Override
  public void stop()
  {
    LOGGER.info(Logging.logMessage("SnowflakeSinkTask[ID:{}]:stop", this.id));
    if (sink != null)
    {
      this.sink.closeAll();
    }
  }

  /**
   * init ingestion task in Sink service
   *
   * @param partitions - The list of all partitions that are now assigned to
   *                   the task
   */
  @Override
  public void open(final Collection<TopicPartition> partitions)
  {
    LOGGER.info(Logging.logMessage(
      "SnowflakeSinkTask[ID:{}]:open, TopicPartitions: {}", this.id, partitions
    ));
    partitions.forEach(tp -> this.sink.startTask(Utils.tableName(tp.topic(),
      this.topic2table), tp.topic(), tp.partition()));
  }


  /**
   * close sink service
   * close all running task because the parameter of open function contains all
   * partition info but not only the new partition
   *
   * @param partitions - The list of all partitions that were assigned to the
   *                   task
   */
  @Override
  public void close(final Collection<TopicPartition> partitions)
  {
    LOGGER.info(Logging.logMessage("SnowflakeSinkTask[ID:{}]:close", this.id));
    if (this.sink != null)
    {
      this.sink.close(partitions);
    }
  }

  /**
   * ingest records to Snowflake
   *
   * @param records - collection of records from kafka topic/partitions for
   *                this connector
   */
  @Override
  public void put(final Collection<SinkRecord> records)
  {
    LOGGER.info(Logging.logMessage("SnowflakeSinkTask[ID:{}]:put {} records",
      this.id, records.size()));
    //log more info may impact performance
    getSink().insert(records);
  }

  /**
   * Sync committed offsets
   *
   * @param offsets - the current map of offsets as of the last call to put
   * @return a map of offsets by topic-partition that are safe to commit
   * @throws RetriableException when meet any issue during processing
   */
  @Override
  public Map<TopicPartition, OffsetAndMetadata> preCommit(
    Map<TopicPartition, OffsetAndMetadata> offsets)
    throws RetriableException
  {
    LOGGER.info(Logging.logMessage("SnowflakeSinkTask[ID:{}]:preCommit", this.id));

    if (sink == null || sink.isClosed())
    {
      LOGGER.warn(Logging.logMessage("SnowflakeSinkTask[ID:{}]: sink " +
        "not initialized or closed before preCommit", this.id));
      return offsets;
    }

    Map<TopicPartition, OffsetAndMetadata> committedOffsets = new HashMap<>();
    // it's ok to just log the error since commit can retry
    try
    {
      offsets.forEach(
        (topicPartition, offsetAndMetadata) ->
        {
          long offSet = sink.getOffset(topicPartition);
          if (offSet == 0) {
            committedOffsets.put(topicPartition, offsetAndMetadata);
            //todo: update offset?
          } else {
            committedOffsets.put(topicPartition,
              new OffsetAndMetadata(sink.getOffset(topicPartition)));
          }
        }
      );
    } catch (Exception e)
    {
      LOGGER.error(Logging.logMessage("SnowflakeSinkTask[ID:{}]: Error " +
        "while preCommit: {} ", this.id, e.getMessage()));
      return offsets;
    }

    return committedOffsets;
  }

  /**
   * @return connector version
   */
  @Override
  public String version()
  {
    return Utils.VERSION;
  }

  /**
   * parse topic to table map
   *
   * @param config connector config file
   * @return result map
   */
  static Map<String, String> getTopicToTableMap(Map<String, String> config)
  {
    if (config.containsKey(SnowflakeSinkConnectorConfig.TOPICS_TABLES_MAP))
    {
      Map<String, String> result =
        Utils.parseTopicToTableMap(config.get(SnowflakeSinkConnectorConfig.TOPICS_TABLES_MAP));
      if (result != null)
      {
        return result;
      }
      LOGGER.error(Logging.logMessage(
        "Invalid Input, Topic2Table Map disabled"
      ));
    }
    return new HashMap<>();

  }


  /**
   * wait for specific status
   *
   * @param func status checker
   */
  private static void waitFor(Supplier<Boolean> func) throws InterruptedException,
    TimeoutException
  {
    for (int i = 0; i < REPEAT_TIME; i++)
    {
      if (func.get())
      {
        return;
      }
      Thread.sleep(WAIT_TIME);
    }
    throw new TimeoutException();
  }

}
