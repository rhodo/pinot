/**
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
package org.apache.pinot.segment.local.startree;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.File;
import java.io.IOException;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.TreeMap;
import javax.annotation.Nullable;
import org.apache.commons.configuration2.PropertiesConfiguration;
import org.apache.commons.io.FileUtils;
import org.apache.pinot.common.request.Literal;
import org.apache.pinot.common.request.context.ExpressionContext;
import org.apache.pinot.segment.local.startree.v2.builder.StarTreeV2BuilderConfig;
import org.apache.pinot.segment.spi.AggregationFunctionType;
import org.apache.pinot.segment.spi.Constants;
import org.apache.pinot.segment.spi.SegmentMetadata;
import org.apache.pinot.segment.spi.index.startree.AggregationFunctionColumnPair;
import org.apache.pinot.segment.spi.index.startree.AggregationSpec;
import org.apache.pinot.segment.spi.index.startree.StarTreeV2Constants;
import org.apache.pinot.segment.spi.index.startree.StarTreeV2Metadata;
import org.apache.pinot.segment.spi.memory.PinotDataBuffer;
import org.apache.pinot.segment.spi.store.SegmentDirectoryPaths;
import org.apache.pinot.segment.spi.utils.SegmentMetadataUtils;
import org.apache.pinot.spi.config.table.StarTreeIndexConfig;
import org.apache.pinot.spi.data.Schema;
import org.apache.pinot.spi.utils.CommonConstants;

import static java.nio.charset.StandardCharsets.UTF_8;


/**
 * The {@code StarTreeBuilderUtils} class contains utility methods for star-tree builders.
 */
public class StarTreeBuilderUtils {
  private StarTreeBuilderUtils() {
  }

  public static final int INVALID_ID = -1;

  public static class TreeNode {
    public int _dimensionId = INVALID_ID;
    public int _dimensionValue = INVALID_ID;
    public int _startDocId = INVALID_ID;
    public int _endDocId = INVALID_ID;
    public int _aggregatedDocId = INVALID_ID;
    public int _childDimensionId = INVALID_ID;
    public Map<Integer, TreeNode> _children;
  }

  /**
   * Generates the deduplicated star-tree builder configs.
   */
  public static List<StarTreeV2BuilderConfig> generateBuilderConfigs(@Nullable List<StarTreeIndexConfig> indexConfigs,
      boolean enableDefaultStarTree, SegmentMetadata segmentMetadata) {
    List<StarTreeV2BuilderConfig> builderConfigs = new ArrayList<>();
    if (indexConfigs != null) {
      for (StarTreeIndexConfig indexConfig : indexConfigs) {
        StarTreeV2BuilderConfig builderConfig = StarTreeV2BuilderConfig.fromIndexConfig(indexConfig);
        if (!builderConfigs.contains(builderConfig)) {
          builderConfigs.add(builderConfig);
        }
      }
    }
    if (enableDefaultStarTree) {
      StarTreeV2BuilderConfig defaultConfig = StarTreeV2BuilderConfig.generateDefaultConfig(segmentMetadata);
      if (!builderConfigs.contains(defaultConfig)) {
        builderConfigs.add(defaultConfig);
      }
    }
    return builderConfigs;
  }

  public static List<StarTreeV2BuilderConfig> generateBuilderConfigs(@Nullable List<StarTreeIndexConfig> indexConfigs,
      boolean enableDefaultStarTree, Schema schema, JsonNode segmentMetadata) {
    List<StarTreeV2BuilderConfig> builderConfigs = new ArrayList<>();
    if (indexConfigs != null) {
      for (StarTreeIndexConfig indexConfig : indexConfigs) {
        StarTreeV2BuilderConfig builderConfig = StarTreeV2BuilderConfig.fromIndexConfig(indexConfig);
        if (!builderConfigs.contains(builderConfig)) {
          builderConfigs.add(builderConfig);
        }
      }
    }
    if (enableDefaultStarTree) {
      StarTreeV2BuilderConfig defaultConfig =
          StarTreeV2BuilderConfig.generateDefaultConfig(schema, segmentMetadata.get("columns"));
      if (!builderConfigs.contains(defaultConfig)) {
        builderConfigs.add(defaultConfig);
      }
    }
    return builderConfigs;
  }

  /**
   * Serialize the star-tree structure into a file.
   */
  public static void serializeTree(File starTreeFile, TreeNode rootNode, String[] dimensions, int numNodes)
      throws IOException {
    int headerSizeInBytes = computeHeaderByteSize(dimensions);
    long totalSizeInBytes = headerSizeInBytes + (long) numNodes * OffHeapStarTreeNode.SERIALIZABLE_SIZE_IN_BYTES;

    // Backward-compatible: star-tree file is always little-endian
    try (PinotDataBuffer buffer = PinotDataBuffer
        .mapFile(starTreeFile, false, 0, totalSizeInBytes, ByteOrder.LITTLE_ENDIAN,
            "StarTreeBuilderUtils#serializeTree: star-tree buffer")) {
      long offset = writeHeader(buffer, headerSizeInBytes, dimensions, numNodes);
      writeNodes(buffer, offset, rootNode);
    }
  }

  /**
   * Helper method to compute size of the star-tree header in bytes.
   * <p>The header contains the following fields:
   * <ul>
   *   <li>Magic marker (long)</li>
   *   <li>Size of the header (int)</li>
   *   <li>Version (int)</li>
   *   <li>Number of dimensions (int)</li>
   *   <li>For each dimension, index of the dimension (int), number of bytes in the dimension string (int), and the byte
   *   array for the string</li>
   *   <li>Number of nodes in the tree (int)</li>
   * </ul>
   */
  private static int computeHeaderByteSize(String[] dimensions) {
    // Magic marker (8), version (4), size of header (4) and number of dimensions (4)
    int headerSizeInBytes = 20;

    for (String dimension : dimensions) {
      headerSizeInBytes += Integer.BYTES; // For dimension index
      headerSizeInBytes += Integer.BYTES; // For length of dimension name
      headerSizeInBytes += dimension.getBytes(UTF_8).length; // For dimension name
    }

    headerSizeInBytes += Integer.BYTES; // For number of nodes.
    return headerSizeInBytes;
  }

  /**
   * Helper method to write the header into the data buffer.
   */
  private static int writeHeader(PinotDataBuffer dataBuffer, int headerSizeInBytes, String[] dimensions, int numNodes) {
    int offset = 0;

    dataBuffer.putLong(offset, OffHeapStarTree.MAGIC_MARKER);
    offset += Long.BYTES;

    dataBuffer.putInt(offset, OffHeapStarTree.VERSION);
    offset += Integer.BYTES;

    dataBuffer.putInt(offset, headerSizeInBytes);
    offset += Integer.BYTES;

    int numDimensions = dimensions.length;
    dataBuffer.putInt(offset, numDimensions);
    offset += Integer.BYTES;

    for (int i = 0; i < numDimensions; i++) {
      dataBuffer.putInt(offset, i);
      offset += Integer.BYTES;

      String dimension = dimensions[i];
      byte[] dimensionBytes = dimension.getBytes(UTF_8);
      int dimensionLength = dimensionBytes.length;
      dataBuffer.putInt(offset, dimensionLength);
      offset += Integer.BYTES;

      dataBuffer.readFrom(offset, dimensionBytes, 0, dimensionLength);
      offset += dimensionLength;
    }

    dataBuffer.putInt(offset, numNodes);
    offset += Integer.BYTES;

    return offset;
  }

  /**
   * Helper method to traverse star-tree using BFS and write nodes into the data buffer.
   */
  private static void writeNodes(PinotDataBuffer dataBuffer, long offset, TreeNode rootNode) {
    Queue<TreeNode> queue = new LinkedList<>();
    queue.add(rootNode);

    int currentNodeId = 0;
    while (!queue.isEmpty()) {
      TreeNode node = queue.remove();

      if (node._children == null) {
        offset = writeNode(dataBuffer, offset, node, INVALID_ID, INVALID_ID);
      } else {
        // Sort all children nodes based on dimension value
        List<TreeNode> sortedChildren = new ArrayList<>(node._children.values());
        sortedChildren.sort((o1, o2) -> Integer.compare(o1._dimensionValue, o2._dimensionValue));

        int firstChildId = currentNodeId + queue.size() + 1;
        int lastChildId = firstChildId + sortedChildren.size() - 1;
        offset = writeNode(dataBuffer, offset, node, firstChildId, lastChildId);

        queue.addAll(sortedChildren);
      }

      currentNodeId++;
    }
  }

  /**
   * Helper method to write one node into the data buffer.
   */
  private static long writeNode(PinotDataBuffer dataBuffer, long offset, TreeNode node, int firstChildId,
      int lastChildId) {
    dataBuffer.putInt(offset, node._dimensionId);
    offset += Integer.BYTES;

    dataBuffer.putInt(offset, node._dimensionValue);
    offset += Integer.BYTES;

    dataBuffer.putInt(offset, node._startDocId);
    offset += Integer.BYTES;

    dataBuffer.putInt(offset, node._endDocId);
    offset += Integer.BYTES;

    dataBuffer.putInt(offset, node._aggregatedDocId);
    offset += Integer.BYTES;

    dataBuffer.putInt(offset, firstChildId);
    offset += Integer.BYTES;

    dataBuffer.putInt(offset, lastChildId);
    offset += Integer.BYTES;

    return offset;
  }

  /**
   * Returns {@code true} if the given star-tree builder configs do not match the star-tree metadata, in which case the
   * relevant star-trees need to be added/removed, {@code false} otherwise.
   */
  public static boolean shouldModifyExistingStarTrees(List<StarTreeV2BuilderConfig> builderConfigs,
      List<StarTreeV2Metadata> metadataList) {
    int numStarTrees = builderConfigs.size();
    if (metadataList.size() != numStarTrees) {
      return true;
    }
    for (int i = 0; i < numStarTrees; i++) {
      StarTreeV2BuilderConfig builderConfig = builderConfigs.get(i);
      StarTreeV2Metadata metadata = metadataList.get(i);
      if (!builderConfig.getDimensionsSplitOrder().equals(metadata.getDimensionsSplitOrder())) {
        return true;
      }
      if (!builderConfig.getSkipStarNodeCreationForDimensions()
          .equals(metadata.getSkipStarNodeCreationForDimensions())) {
        return true;
      }
      if (builderConfig.getMaxLeafRecords() != metadata.getMaxLeafRecords()) {
        return true;
      }
      TreeMap<AggregationFunctionColumnPair, AggregationSpec> newSpecs = builderConfig.getAggregationSpecs();
      TreeMap<AggregationFunctionColumnPair, AggregationSpec> existingSpecs = metadata.getAggregationSpecs();
      if (newSpecs.size() != existingSpecs.size()) {
        return true;
      }
      for (Map.Entry<AggregationFunctionColumnPair, AggregationSpec> entry : newSpecs.entrySet()) {
        AggregationFunctionColumnPair functionColumnPair = entry.getKey();
        AggregationSpec newSpec = entry.getValue();
        AggregationSpec existingSpec = existingSpecs.get(functionColumnPair);
        if (existingSpec == null) {
          return true;
        }
        if (newSpec.shouldModifyStarTree(existingSpec)) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * Returns {@code true} if the given star-tree builder configs are equal, {@code false} otherwise.
   */
  public static boolean areStarTreeBuilderConfigListsEqual(List<StarTreeV2BuilderConfig> builderConfig1,
      List<StarTreeV2BuilderConfig> builderConfig2) {
    int numStarTrees = builderConfig1.size();
    if (builderConfig2.size() != numStarTrees) {
      return false;
    }
    for (int i = 0; i < numStarTrees; i++) {
      StarTreeV2BuilderConfig builderConfigToCompare1 = builderConfig1.get(i);
      StarTreeV2BuilderConfig builderConfigToCompare2 = builderConfig2.get(i);
      if (!builderConfigToCompare1.equals(builderConfigToCompare2)) {
        return false;
      }
    }
    return true;
  }

  /**
   * Removes all the star-trees from the given segment.
   */
  public static void removeStarTrees(File indexDir)
      throws Exception {
    // Remove the star-tree metadata
    PropertiesConfiguration metadataProperties = SegmentMetadataUtils.getPropertiesConfiguration(indexDir);
    metadataProperties.subset(StarTreeV2Constants.MetadataKey.STAR_TREE_SUBSET).clear();
    SegmentMetadataUtils.savePropertiesConfiguration(metadataProperties, indexDir);

    // Remove the index file and index map file
    File segmentDirectory = SegmentDirectoryPaths.findSegmentDirectory(indexDir);
    FileUtils.forceDelete(new File(segmentDirectory, StarTreeV2Constants.INDEX_FILE_NAME));
    FileUtils.forceDelete(new File(segmentDirectory, StarTreeV2Constants.INDEX_MAP_FILE_NAME));
  }

  public static List<ExpressionContext> expressionContextFromFunctionParameters(
      AggregationFunctionType aggregationFunctionType, Map<String, Object> functionParameters) {
    List<ExpressionContext> expressionContexts = new ArrayList<>();

    switch (aggregationFunctionType) {
      case DISTINCTCOUNTHLL:
      case DISTINCTCOUNTRAWHLL: {
        if (functionParameters.containsKey(Constants.HLL_LOG2M_KEY)) {
          expressionContexts.add(ExpressionContext.forLiteral(
              Literal.intValue(Integer.parseInt(String.valueOf(functionParameters.get(Constants.HLL_LOG2M_KEY))))));
        }
        break;
      }
      case DISTINCTCOUNTHLLPLUS:
      case DISTINCTCOUNTRAWHLLPLUS: {
        if (functionParameters.containsKey(Constants.HLLPLUS_ULL_P_KEY) && functionParameters.containsKey(
            Constants.HLLPLUS_SP_KEY)) {
          expressionContexts.add(ExpressionContext.forLiteral(
              Literal.intValue(Integer.parseInt(String.valueOf(functionParameters.get(Constants.HLLPLUS_ULL_P_KEY))))));
          expressionContexts.add(ExpressionContext.forLiteral(
              Literal.intValue(Integer.parseInt(String.valueOf(functionParameters.get(Constants.HLLPLUS_SP_KEY))))));
        } else if (functionParameters.containsKey(Constants.HLLPLUS_ULL_P_KEY)) {
          expressionContexts.add(ExpressionContext.forLiteral(
              Literal.intValue(Integer.parseInt(String.valueOf(functionParameters.get(Constants.HLLPLUS_ULL_P_KEY))))));
        } else if (functionParameters.containsKey(Constants.HLLPLUS_SP_KEY)) {
          expressionContexts.add(ExpressionContext.forLiteral(
              Literal.intValue(Integer.parseInt(String.valueOf(CommonConstants.Helix.DEFAULT_HYPERLOGLOG_PLUS_P)))));
          expressionContexts.add(ExpressionContext.forLiteral(
              Literal.intValue(Integer.parseInt(String.valueOf(functionParameters.get(Constants.HLLPLUS_SP_KEY))))));
        }
        break;
      }
      case DISTINCTCOUNTULL:
      case DISTINCTCOUNTRAWULL: {
        if (functionParameters.containsKey(Constants.HLLPLUS_ULL_P_KEY)) {
          expressionContexts.add(ExpressionContext.forLiteral(
              Literal.intValue(Integer.parseInt(String.valueOf(functionParameters.get(Constants.HLLPLUS_ULL_P_KEY))))));
        }
        break;
      }
      case DISTINCTCOUNTCPCSKETCH:
      case DISTINCTCOUNTRAWCPCSKETCH: {
        if (functionParameters.containsKey(Constants.CPCSKETCH_LGK_KEY)) {
          expressionContexts.add(ExpressionContext.forLiteral(
              Literal.intValue(Integer.parseInt(String.valueOf(functionParameters.get(Constants.CPCSKETCH_LGK_KEY))))));
        }
        break;
      }
      case SUMPRECISION: {
        if (functionParameters.containsKey(Constants.SUMPRECISION_PRECISION_KEY)) {
          expressionContexts.add(ExpressionContext.forLiteral(Literal.intValue(
              Integer.parseInt(String.valueOf(functionParameters.get(Constants.SUMPRECISION_PRECISION_KEY))))));
        }
        break;
      }
      case PERCENTILETDIGEST:
      case PERCENTILERAWTDIGEST: {
        if (functionParameters.containsKey(Constants.PERCENTILETDIGEST_COMPRESSION_FACTOR_KEY)) {
          expressionContexts.add(ExpressionContext.forLiteral(
              Literal.intValue(Integer.parseInt(String.valueOf(
                  functionParameters.get(Constants.PERCENTILETDIGEST_COMPRESSION_FACTOR_KEY))))));
        }
        break;
      }
      case DISTINCTCOUNTTHETASKETCH:
      case DISTINCTCOUNTRAWTHETASKETCH: {
        if (functionParameters.containsKey(Constants.THETA_TUPLE_SKETCH_NOMINAL_ENTRIES)) {
          expressionContexts.add(ExpressionContext.forLiteral(Literal.intValue(
              Integer.parseInt(String.valueOf(functionParameters.get(Constants.THETA_TUPLE_SKETCH_NOMINAL_ENTRIES))))));
        }
        break;
      }
      case DISTINCTCOUNTTUPLESKETCH:
      case DISTINCTCOUNTRAWINTEGERSUMTUPLESKETCH: {
        if (functionParameters.containsKey(Constants.THETA_TUPLE_SKETCH_NOMINAL_ENTRIES)) {
          expressionContexts.add(ExpressionContext.forLiteral(Literal.intValue(
              Integer.parseInt(String.valueOf(functionParameters.get(Constants.THETA_TUPLE_SKETCH_NOMINAL_ENTRIES))))));
        }
        break;
      }
      default:
        break;
    }

    return expressionContexts;
  }
}
