/*
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

package org.apache.flink.table.catalog.hive.util;

import org.apache.flink.connectors.hive.util.HivePartitionUtils;
import org.apache.flink.table.catalog.CatalogPartitionSpec;
import org.apache.flink.table.catalog.exceptions.CatalogException;
import org.apache.flink.table.catalog.hive.client.HiveMetastoreClientWrapper;
import org.apache.flink.table.catalog.hive.client.HiveShim;
import org.apache.flink.table.catalog.hive.client.HiveShimLoader;
import org.apache.flink.table.catalog.stats.CatalogColumnStatisticsDataBase;
import org.apache.flink.table.catalog.stats.CatalogColumnStatisticsDataBinary;
import org.apache.flink.table.catalog.stats.CatalogColumnStatisticsDataBoolean;
import org.apache.flink.table.catalog.stats.CatalogColumnStatisticsDataDate;
import org.apache.flink.table.catalog.stats.CatalogColumnStatisticsDataDouble;
import org.apache.flink.table.catalog.stats.CatalogColumnStatisticsDataLong;
import org.apache.flink.table.catalog.stats.CatalogColumnStatisticsDataString;
import org.apache.flink.table.catalog.stats.CatalogTableStatistics;
import org.apache.flink.table.catalog.stats.Date;
import org.apache.flink.table.plan.stats.TableStats;
import org.apache.flink.table.types.DataType;
import org.apache.flink.table.types.logical.LogicalType;
import org.apache.flink.table.types.logical.LogicalTypeRoot;

import org.apache.commons.lang3.StringUtils;
import org.apache.hadoop.hive.common.StatsSetupConst;
import org.apache.hadoop.hive.common.type.HiveDecimal;
import org.apache.hadoop.hive.metastore.api.BinaryColumnStatsData;
import org.apache.hadoop.hive.metastore.api.BooleanColumnStatsData;
import org.apache.hadoop.hive.metastore.api.ColumnStatistics;
import org.apache.hadoop.hive.metastore.api.ColumnStatisticsData;
import org.apache.hadoop.hive.metastore.api.ColumnStatisticsDesc;
import org.apache.hadoop.hive.metastore.api.ColumnStatisticsObj;
import org.apache.hadoop.hive.metastore.api.Decimal;
import org.apache.hadoop.hive.metastore.api.DecimalColumnStatsData;
import org.apache.hadoop.hive.metastore.api.DoubleColumnStatsData;
import org.apache.hadoop.hive.metastore.api.FieldSchema;
import org.apache.hadoop.hive.metastore.api.LongColumnStatsData;
import org.apache.hadoop.hive.metastore.api.Partition;
import org.apache.hadoop.hive.metastore.api.StorageDescriptor;
import org.apache.hadoop.hive.metastore.api.StringColumnStatsData;
import org.apache.hadoop.hive.metastore.api.Table;
import org.apache.hadoop.hive.serde2.typeinfo.TypeInfoUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.apache.flink.util.Preconditions.checkNotNull;

/** Utils for stats of HiveCatalog. */
public class HiveStatsUtil {
    private static final Logger LOG = LoggerFactory.getLogger(HiveStatsUtil.class);

    private static final int DEFAULT_UNKNOWN_STATS_VALUE = -1;

    private HiveStatsUtil() {}

    /** Create a map of Flink column stats from the given Hive column stats. */
    public static Map<String, CatalogColumnStatisticsDataBase> createCatalogColumnStats(
            @Nonnull List<ColumnStatisticsObj> hiveColStats, String hiveVersion) {
        checkNotNull(hiveColStats, "hiveColStats can not be null");
        Map<String, CatalogColumnStatisticsDataBase> colStats = new HashMap<>();
        for (ColumnStatisticsObj colStatsObj : hiveColStats) {
            CatalogColumnStatisticsDataBase columnStats =
                    createTableColumnStats(
                            HiveTypeUtil.toFlinkType(
                                    TypeInfoUtils.getTypeInfoFromTypeString(
                                            colStatsObj.getColType())),
                            colStatsObj.getStatsData(),
                            hiveVersion);
            colStats.put(colStatsObj.getColName(), columnStats);
        }

        return colStats;
    }

    /** Get column statistic for partition columns. */
    public static Map<String, CatalogColumnStatisticsDataBase> getCatalogPartitionColumnStats(
            HiveMetastoreClientWrapper client,
            HiveShim hiveShim,
            Table hiveTable,
            String partitionName,
            List<FieldSchema> partitionColsSchema,
            String defaultPartitionName) {
        Map<String, CatalogColumnStatisticsDataBase> partitionColumnStats = new HashMap<>();
        List<String> partitionCols = new ArrayList<>(partitionColsSchema.size());
        List<LogicalType> partitionColsType = new ArrayList<>(partitionColsSchema.size());
        for (FieldSchema fieldSchema : partitionColsSchema) {
            partitionCols.add(fieldSchema.getName());
            partitionColsType.add(
                    HiveTypeUtil.toFlinkType(
                                    TypeInfoUtils.getTypeInfoFromTypeString(fieldSchema.getType()))
                            .getLogicalType());
        }

        // the partition column and values for the partition column
        Map<String, Object> partitionColValues = new HashMap<>();
        CatalogPartitionSpec partitionSpec =
                HivePartitionUtils.createPartitionSpec(partitionName, defaultPartitionName);
        for (int i = 0; i < partitionCols.size(); i++) {
            String partitionCol = partitionCols.get(i);
            String partitionStrVal = partitionSpec.getPartitionSpec().get(partitionCols.get(i));
            if (partitionStrVal == null) {
                partitionColValues.put(partitionCol, null);
            } else {
                partitionColValues.put(
                        partitionCol,
                        HivePartitionUtils.restorePartitionValueFromType(
                                hiveShim,
                                partitionStrVal,
                                partitionColsType.get(i),
                                defaultPartitionName));
            }
        }

        // calculate statistic for each partition column
        for (int i = 0; i < partitionCols.size(); i++) {
            Object partitionValue = partitionColValues.get(partitionCols.get(i));
            LogicalType logicalType = partitionColsType.get(i);
            CatalogColumnStatisticsDataBase catalogColumnStatistics =
                    getPartitionColumnStats(
                            client,
                            hiveTable,
                            logicalType,
                            partitionValue,
                            i,
                            defaultPartitionName);
            if (catalogColumnStatistics != null) {
                partitionColumnStats.put(partitionCols.get(i), catalogColumnStatistics);
            }
        }

        return partitionColumnStats;
    }

    /**
     * Get statistics for a specific partition column.
     *
     * @param logicalType the specific partition column's logical type
     * @param partitionValue the partition value for the specific partition column
     * @param partitionColIndex the index of the specific partition column
     * @param defaultPartitionName the default partition name for null value
     */
    private static CatalogColumnStatisticsDataBase getPartitionColumnStats(
            HiveMetastoreClientWrapper client,
            Table hiveTable,
            LogicalType logicalType,
            Object partitionValue,
            int partitionColIndex,
            String defaultPartitionName) {
        switch (logicalType.getTypeRoot()) {
            case CHAR:
            case VARCHAR:
                {
                    Long maxLength = null;
                    Double avgLength = null;
                    Long nullCount = 0L;
                    if (partitionValue == null) {
                        nullCount =
                                getPartitionColumnNullCount(
                                        client, hiveTable, partitionColIndex, defaultPartitionName);
                    } else {
                        long valLength = ((String) partitionValue).length();
                        maxLength = valLength;
                        avgLength = (double) valLength;
                    }
                    return new CatalogColumnStatisticsDataString(
                            maxLength, avgLength, 1L, nullCount);
                }
            case BOOLEAN:
                {
                    long trueCount = 0L;
                    long falseCount = 0L;
                    Long nullCount = 0L;
                    if (partitionValue == null) {
                        nullCount =
                                getPartitionColumnNullCount(
                                        client, hiveTable, partitionColIndex, defaultPartitionName);
                    } else {
                        Boolean boolVal = (Boolean) partitionValue;
                        if (boolVal) {
                            trueCount = 1L;
                        } else {
                            falseCount = 1L;
                        }
                    }
                    return new CatalogColumnStatisticsDataBoolean(trueCount, falseCount, nullCount);
                }
            case TINYINT:
            case SMALLINT:
            case INTEGER:
            case BIGINT:
                {
                    Long min = null;
                    Long max = null;
                    Long nullCount = 0L;
                    if (partitionValue == null) {
                        nullCount =
                                getPartitionColumnNullCount(
                                        client, hiveTable, partitionColIndex, defaultPartitionName);
                    } else {
                        min = ((Number) partitionValue).longValue();
                        max = min;
                    }
                    return new CatalogColumnStatisticsDataLong(min, max, 1L, nullCount);
                }
            case FLOAT:
            case DOUBLE:
            case DECIMAL:
                {
                    Double min = null;
                    Double max = null;
                    Long nullCount = 0L;
                    if (partitionValue == null) {
                        nullCount =
                                getPartitionColumnNullCount(
                                        client, hiveTable, partitionColIndex, defaultPartitionName);
                    } else {
                        min = ((Number) partitionValue).doubleValue();
                        max = min;
                    }
                    return new CatalogColumnStatisticsDataDouble(min, max, 1L, nullCount);
                }
            case DATE:
            case TIMESTAMP_WITH_LOCAL_TIME_ZONE:
                {
                    Date min = null;
                    Date max = null;
                    Long nullCount = 0L;
                    if (partitionValue == null) {
                        nullCount =
                                getPartitionColumnNullCount(
                                        client, hiveTable, partitionColIndex, defaultPartitionName);
                    } else {
                        if (partitionValue instanceof LocalDate) {
                            min = new Date(((LocalDate) partitionValue).toEpochDay());
                        } else if (partitionValue instanceof LocalDateTime) {
                            min =
                                    new Date(
                                            ((LocalDateTime) partitionValue)
                                                    .toLocalDate()
                                                    .toEpochDay());
                        }
                        max = min;
                    }
                    return new CatalogColumnStatisticsDataDate(min, max, 1L, nullCount);
                }
            default:
                return null;
        }
    }

    /**
     * Get the null count for the {@param partitionColIndex} partition column in table {@param
     * hiveTable}.
     *
     * <p>To get the null count, it will first list all the partitions whose {@param
     * partitionColIndex} partition column is null, and merge the partition's statistic to get the
     * total rows, which is exactly null count for the {@param partitionColIndex} partition column.
     */
    private static Long getPartitionColumnNullCount(
            HiveMetastoreClientWrapper client,
            Table hiveTable,
            int partitionColIndex,
            String defaultPartitionName) {
        // get the partial partition values
        List<String> partialPartitionVals =
                getPartialPartitionVals(partitionColIndex, defaultPartitionName);
        try {
            // list all the partitions that match the partial partition values
            List<Partition> partitions =
                    client.listPartitions(
                            hiveTable.getDbName(),
                            hiveTable.getTableName(),
                            partialPartitionVals,
                            (short) -1);
            List<TableStats> catalogTableStatistics =
                    partitions.stream()
                            .map(
                                    p ->
                                            new TableStats(
                                                    HiveStatsUtil.createCatalogTableStatistics(
                                                                    p.getParameters())
                                                            .getRowCount()))
                            .collect(Collectors.toList());

            Set<String> partitionKeys = getFieldNames(hiveTable.getPartitionKeys());
            TableStats resultTableStats =
                    catalogTableStatistics.stream()
                            .reduce((s1, s2) -> s1.merge(s2, partitionKeys))
                            .orElse(TableStats.UNKNOWN);
            if (resultTableStats == TableStats.UNKNOWN || resultTableStats.getRowCount() < 0) {
                return null;
            } else {
                return resultTableStats.getRowCount();
            }

        } catch (Exception e) {
            LOG.warn(
                    "Can't list partition for table `{}.{}`, partition value {}.",
                    hiveTable.getDbName(),
                    hiveTable.getTableName(),
                    partialPartitionVals);
        }
        return null;
    }

    /** Get field names from field schemas. */
    private static Set<String> getFieldNames(List<FieldSchema> fieldSchemas) {
        Set<String> names = new HashSet<>();
        for (FieldSchema fs : fieldSchemas) {
            names.add(fs.getName());
        }
        return names;
    }

    public static CatalogTableStatistics createCatalogTableStatistics(
            Map<String, String> parameters) {
        return new CatalogTableStatistics(
                parsePositiveLongStat(parameters, StatsSetupConst.ROW_COUNT),
                parsePositiveIntStat(parameters, StatsSetupConst.NUM_FILES),
                parsePositiveLongStat(parameters, StatsSetupConst.TOTAL_SIZE),
                parsePositiveLongStat(parameters, StatsSetupConst.RAW_DATA_SIZE));
    }

    /**
     * Get the partial partition values whose {@param partitionColIndex} partition column value will
     * be {@param defaultPartitionName} and the value for preceding partition column will empty
     * string.
     *
     * <p>For example, if partitionColIndex = 3, defaultPartitionName = __default_partition__, the
     * partial partition values will be ["", "", "", __default_partition__].
     *
     * <p>It's be useful when we want to list all the these Hive's partitions, of which the value
     * for one specific partition column is null.
     */
    private static List<String> getPartialPartitionVals(
            int partitionColIndex, String defaultPartitionName) {
        List<String> partitionValues = new ArrayList<>();
        for (int i = 0; i < partitionColIndex; i++) {
            partitionValues.add(StringUtils.EMPTY);
        }
        partitionValues.add(defaultPartitionName);
        return partitionValues;
    }

    /** Create columnStatistics from the given Hive column stats of a hive table. */
    public static ColumnStatistics createTableColumnStats(
            Table hiveTable,
            Map<String, CatalogColumnStatisticsDataBase> colStats,
            String hiveVersion) {
        ColumnStatisticsDesc desc =
                new ColumnStatisticsDesc(true, hiveTable.getDbName(), hiveTable.getTableName());
        return createHiveColumnStatistics(colStats, hiveTable.getSd(), desc, hiveVersion);
    }

    /** Create columnStatistics from the given Hive column stats of a hive partition. */
    public static ColumnStatistics createPartitionColumnStats(
            Partition hivePartition,
            String partName,
            Map<String, CatalogColumnStatisticsDataBase> colStats,
            String hiveVersion) {
        ColumnStatisticsDesc desc =
                new ColumnStatisticsDesc(
                        false, hivePartition.getDbName(), hivePartition.getTableName());
        desc.setPartName(partName);
        return createHiveColumnStatistics(colStats, hivePartition.getSd(), desc, hiveVersion);
    }

    private static ColumnStatistics createHiveColumnStatistics(
            Map<String, CatalogColumnStatisticsDataBase> colStats,
            StorageDescriptor sd,
            ColumnStatisticsDesc desc,
            String hiveVersion) {
        List<ColumnStatisticsObj> colStatsList = new ArrayList<>();

        for (FieldSchema field : sd.getCols()) {
            String hiveColName = field.getName();
            String hiveColType = field.getType();
            CatalogColumnStatisticsDataBase flinkColStat = colStats.get(field.getName());
            if (null != flinkColStat) {
                ColumnStatisticsData statsData =
                        getColumnStatisticsData(
                                HiveTypeUtil.toFlinkType(
                                        TypeInfoUtils.getTypeInfoFromTypeString(hiveColType)),
                                flinkColStat,
                                hiveVersion);
                ColumnStatisticsObj columnStatisticsObj =
                        new ColumnStatisticsObj(hiveColName, hiveColType, statsData);
                colStatsList.add(columnStatisticsObj);
            }
        }

        return new ColumnStatistics(desc, colStatsList);
    }

    /** Create Flink ColumnStats from Hive ColumnStatisticsData. */
    private static CatalogColumnStatisticsDataBase createTableColumnStats(
            DataType colType, ColumnStatisticsData stats, String hiveVersion) {
        HiveShim hiveShim = HiveShimLoader.loadHiveShim(hiveVersion);
        if (stats.isSetBinaryStats()) {
            BinaryColumnStatsData binaryStats = stats.getBinaryStats();
            return new CatalogColumnStatisticsDataBinary(
                    binaryStats.isSetMaxColLen() ? binaryStats.getMaxColLen() : null,
                    binaryStats.isSetAvgColLen() ? binaryStats.getAvgColLen() : null,
                    binaryStats.isSetNumNulls() ? binaryStats.getNumNulls() : null);
        } else if (stats.isSetBooleanStats()) {
            BooleanColumnStatsData booleanStats = stats.getBooleanStats();
            return new CatalogColumnStatisticsDataBoolean(
                    booleanStats.isSetNumTrues() ? booleanStats.getNumTrues() : null,
                    booleanStats.isSetNumFalses() ? booleanStats.getNumFalses() : null,
                    booleanStats.isSetNumNulls() ? booleanStats.getNumNulls() : null);
        } else if (hiveShim.isDateStats(stats)) {
            return hiveShim.toFlinkDateColStats(stats);
        } else if (stats.isSetDoubleStats()) {
            DoubleColumnStatsData doubleStats = stats.getDoubleStats();
            return new CatalogColumnStatisticsDataDouble(
                    doubleStats.isSetLowValue() ? doubleStats.getLowValue() : null,
                    doubleStats.isSetHighValue() ? doubleStats.getHighValue() : null,
                    doubleStats.isSetNumDVs() ? doubleStats.getNumDVs() : null,
                    doubleStats.isSetNumNulls() ? doubleStats.getNumNulls() : null);
        } else if (stats.isSetLongStats()) {
            LongColumnStatsData longColStats = stats.getLongStats();
            return new CatalogColumnStatisticsDataLong(
                    longColStats.isSetLowValue() ? longColStats.getLowValue() : null,
                    longColStats.isSetHighValue() ? longColStats.getHighValue() : null,
                    longColStats.isSetNumDVs() ? longColStats.getNumDVs() : null,
                    longColStats.isSetNumNulls() ? longColStats.getNumNulls() : null);
        } else if (stats.isSetStringStats()) {
            StringColumnStatsData stringStats = stats.getStringStats();
            return new CatalogColumnStatisticsDataString(
                    stringStats.isSetMaxColLen() ? stringStats.getMaxColLen() : null,
                    stringStats.isSetAvgColLen() ? stringStats.getAvgColLen() : null,
                    stringStats.isSetNumDVs() ? stringStats.getNumDVs() : null,
                    stringStats.isSetNumDVs() ? stringStats.getNumNulls() : null);
        } else if (stats.isSetDecimalStats()) {
            DecimalColumnStatsData decimalStats = stats.getDecimalStats();
            // for now, just return CatalogColumnStatisticsDataDouble for decimal columns
            Double max = null;
            if (decimalStats.isSetHighValue()) {
                max = toHiveDecimal(decimalStats.getHighValue()).doubleValue();
            }
            Double min = null;
            if (decimalStats.isSetLowValue()) {
                min = toHiveDecimal(decimalStats.getLowValue()).doubleValue();
            }
            Long ndv = decimalStats.isSetNumDVs() ? decimalStats.getNumDVs() : null;
            Long nullCount = decimalStats.isSetNumNulls() ? decimalStats.getNumNulls() : null;
            return new CatalogColumnStatisticsDataDouble(min, max, ndv, nullCount);
        } else {
            LOG.warn(
                    "Flink does not support converting ColumnStatisticsData '{}' for Hive column type '{}' yet.",
                    stats,
                    colType);
            return null;
        }
    }

    /**
     * Convert Flink ColumnStats to Hive ColumnStatisticsData according to Hive column type. Note we
     * currently assume that, in Flink, the max and min of ColumnStats will be same type as the
     * Flink column type. For example, for SHORT and Long columns, the max and min of their
     * ColumnStats should be of type SHORT and LONG.
     */
    private static ColumnStatisticsData getColumnStatisticsData(
            DataType colType, CatalogColumnStatisticsDataBase colStat, String hiveVersion) {
        LogicalTypeRoot type = colType.getLogicalType().getTypeRoot();
        if (type.equals(LogicalTypeRoot.CHAR) || type.equals(LogicalTypeRoot.VARCHAR)) {
            if (colStat instanceof CatalogColumnStatisticsDataString) {
                CatalogColumnStatisticsDataString stringColStat =
                        (CatalogColumnStatisticsDataString) colStat;
                StringColumnStatsData hiveStringColumnStats = new StringColumnStatsData();
                hiveStringColumnStats.clear();
                if (null != stringColStat.getMaxLength()) {
                    hiveStringColumnStats.setMaxColLen(stringColStat.getMaxLength());
                }
                if (null != stringColStat.getAvgLength()) {
                    hiveStringColumnStats.setAvgColLen(stringColStat.getAvgLength());
                }
                if (null != stringColStat.getNullCount()) {
                    hiveStringColumnStats.setNumNulls(stringColStat.getNullCount());
                }
                if (null != stringColStat.getNdv()) {
                    hiveStringColumnStats.setNumDVs(stringColStat.getNdv());
                }
                return ColumnStatisticsData.stringStats(hiveStringColumnStats);
            }
        } else if (type.equals(LogicalTypeRoot.BOOLEAN)) {
            if (colStat instanceof CatalogColumnStatisticsDataBoolean) {
                CatalogColumnStatisticsDataBoolean booleanColStat =
                        (CatalogColumnStatisticsDataBoolean) colStat;
                BooleanColumnStatsData hiveBoolStats = new BooleanColumnStatsData();
                hiveBoolStats.clear();
                if (null != booleanColStat.getTrueCount()) {
                    hiveBoolStats.setNumTrues(booleanColStat.getTrueCount());
                }
                if (null != booleanColStat.getFalseCount()) {
                    hiveBoolStats.setNumFalses(booleanColStat.getFalseCount());
                }
                if (null != booleanColStat.getNullCount()) {
                    hiveBoolStats.setNumNulls(booleanColStat.getNullCount());
                }
                return ColumnStatisticsData.booleanStats(hiveBoolStats);
            }
        } else if (type.equals(LogicalTypeRoot.TINYINT)
                || type.equals(LogicalTypeRoot.SMALLINT)
                || type.equals(LogicalTypeRoot.INTEGER)
                || type.equals(LogicalTypeRoot.BIGINT)
                || type.equals(LogicalTypeRoot.TIMESTAMP_WITH_LOCAL_TIME_ZONE)
                || type.equals(LogicalTypeRoot.TIME_WITHOUT_TIME_ZONE)
                || type.equals(LogicalTypeRoot.TIMESTAMP_WITH_TIME_ZONE)) {
            if (colStat instanceof CatalogColumnStatisticsDataLong) {
                CatalogColumnStatisticsDataLong longColStat =
                        (CatalogColumnStatisticsDataLong) colStat;
                LongColumnStatsData hiveLongColStats = new LongColumnStatsData();
                hiveLongColStats.clear();
                if (null != longColStat.getMax()) {
                    hiveLongColStats.setHighValue(longColStat.getMax());
                }
                if (null != longColStat.getMin()) {
                    hiveLongColStats.setLowValue(longColStat.getMin());
                }
                if (null != longColStat.getNdv()) {
                    hiveLongColStats.setNumDVs(longColStat.getNdv());
                }
                if (null != longColStat.getNullCount()) {
                    hiveLongColStats.setNumNulls(longColStat.getNullCount());
                }
                return ColumnStatisticsData.longStats(hiveLongColStats);
            }
        } else if (type.equals(LogicalTypeRoot.FLOAT) || type.equals(LogicalTypeRoot.DOUBLE)) {
            if (colStat instanceof CatalogColumnStatisticsDataDouble) {
                CatalogColumnStatisticsDataDouble doubleColumnStatsData =
                        (CatalogColumnStatisticsDataDouble) colStat;
                DoubleColumnStatsData hiveFloatStats = new DoubleColumnStatsData();
                hiveFloatStats.clear();
                if (null != doubleColumnStatsData.getMax()) {
                    hiveFloatStats.setHighValue(doubleColumnStatsData.getMax());
                }
                if (null != doubleColumnStatsData.getMin()) {
                    hiveFloatStats.setLowValue(doubleColumnStatsData.getMin());
                }
                if (null != doubleColumnStatsData.getNullCount()) {
                    hiveFloatStats.setNumNulls(doubleColumnStatsData.getNullCount());
                }
                if (null != doubleColumnStatsData.getNdv()) {
                    hiveFloatStats.setNumDVs(doubleColumnStatsData.getNdv());
                }
                return ColumnStatisticsData.doubleStats(hiveFloatStats);
            }
        } else if (type.equals(LogicalTypeRoot.DATE)) {
            if (colStat instanceof CatalogColumnStatisticsDataDate) {
                HiveShim hiveShim = HiveShimLoader.loadHiveShim(hiveVersion);
                return hiveShim.toHiveDateColStats((CatalogColumnStatisticsDataDate) colStat);
            }
        } else if (type.equals(LogicalTypeRoot.VARBINARY) || type.equals(LogicalTypeRoot.BINARY)) {
            if (colStat instanceof CatalogColumnStatisticsDataBinary) {
                CatalogColumnStatisticsDataBinary binaryColumnStatsData =
                        (CatalogColumnStatisticsDataBinary) colStat;
                BinaryColumnStatsData hiveBinaryColumnStats = new BinaryColumnStatsData();
                hiveBinaryColumnStats.clear();
                if (null != binaryColumnStatsData.getMaxLength()) {
                    hiveBinaryColumnStats.setMaxColLen(binaryColumnStatsData.getMaxLength());
                }
                if (null != binaryColumnStatsData.getAvgLength()) {
                    hiveBinaryColumnStats.setAvgColLen(binaryColumnStatsData.getAvgLength());
                }
                if (null != binaryColumnStatsData.getNullCount()) {
                    hiveBinaryColumnStats.setNumNulls(binaryColumnStatsData.getNullCount());
                }
                return ColumnStatisticsData.binaryStats(hiveBinaryColumnStats);
            }
        } else if (type.equals(LogicalTypeRoot.DECIMAL)) {
            if (colStat instanceof CatalogColumnStatisticsDataDouble) {
                CatalogColumnStatisticsDataDouble flinkStats =
                        (CatalogColumnStatisticsDataDouble) colStat;
                DecimalColumnStatsData hiveStats = new DecimalColumnStatsData();
                if (flinkStats.getMax() != null) {
                    // in older versions we cannot create HiveDecimal from Double, so convert Double
                    // to BigDecimal first
                    hiveStats.setHighValue(
                            toThriftDecimal(
                                    HiveDecimal.create(BigDecimal.valueOf(flinkStats.getMax()))));
                }
                if (flinkStats.getMin() != null) {
                    hiveStats.setLowValue(
                            toThriftDecimal(
                                    HiveDecimal.create(BigDecimal.valueOf(flinkStats.getMin()))));
                }
                if (flinkStats.getNdv() != null) {
                    hiveStats.setNumDVs(flinkStats.getNdv());
                }
                if (flinkStats.getNullCount() != null) {
                    hiveStats.setNumNulls(flinkStats.getNullCount());
                }
                return ColumnStatisticsData.decimalStats(hiveStats);
            }
        }
        throw new CatalogException(
                String.format(
                        "Flink does not support converting ColumnStats '%s' for Hive column "
                                + "type '%s' yet",
                        colStat, colType));
    }

    private static Decimal toThriftDecimal(HiveDecimal hiveDecimal) {
        // the constructor signature changed in 3.x. use default constructor and set each field...
        Decimal res = new Decimal();
        res.setUnscaled(ByteBuffer.wrap(hiveDecimal.unscaledValue().toByteArray()));
        res.setScale((short) hiveDecimal.scale());
        return res;
    }

    private static HiveDecimal toHiveDecimal(Decimal decimal) {
        return HiveDecimal.create(new BigInteger(decimal.getUnscaled()), decimal.getScale());
    }

    public static int parsePositiveIntStat(Map<String, String> parameters, String key) {
        String value = parameters.get(key);
        if (value == null) {
            return DEFAULT_UNKNOWN_STATS_VALUE;
        } else {
            int v = Integer.parseInt(value);
            return v > 0 ? v : DEFAULT_UNKNOWN_STATS_VALUE;
        }
    }

    public static long parsePositiveLongStat(Map<String, String> parameters, String key) {
        String value = parameters.get(key);
        if (value == null) {
            return DEFAULT_UNKNOWN_STATS_VALUE;
        } else {
            long v = Long.parseLong(value);
            return v > 0 ? v : DEFAULT_UNKNOWN_STATS_VALUE;
        }
    }
}
