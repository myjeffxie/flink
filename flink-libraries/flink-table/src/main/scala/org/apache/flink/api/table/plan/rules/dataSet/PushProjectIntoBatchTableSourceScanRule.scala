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

package org.apache.flink.api.table.plan.rules.dataSet

import org.apache.calcite.plan.{RelOptRule, RelOptRuleCall}
import org.apache.calcite.plan.RelOptRule.{none, operand}
import org.apache.calcite.rex.{RexProgram, RexUtil}
import org.apache.flink.api.table.plan.nodes.dataset.{BatchTableSourceScan, DataSetCalc}
import org.apache.flink.api.table.plan.rules.util.DataSetCalcConverter._
import org.apache.flink.api.table.sources.{BatchTableSource, ProjectableTableSource}
import scala.collection.JavaConverters._

/**
  * This rule is responsible for push project into BatchTableSourceScan node
  */
class PushProjectIntoBatchTableSourceScanRule extends RelOptRule(
  operand(classOf[DataSetCalc],
    operand(classOf[BatchTableSourceScan], none)),
  "PushProjectIntoBatchTableSourceScanRule") {

  override def matches(call: RelOptRuleCall) = {
    val scan: BatchTableSourceScan = call.rel(1).asInstanceOf[BatchTableSourceScan]
    scan.tableSource match {
      case _: ProjectableTableSource[_] => true
      case _ => false
    }
  }

  override def onMatch(call: RelOptRuleCall) {
    val calc: DataSetCalc = call.rel(0).asInstanceOf[DataSetCalc]
    val scan: BatchTableSourceScan = call.rel(1).asInstanceOf[BatchTableSourceScan]

    val usedFields: Array[Int] = extractRefInputFields(calc)

    // if no fields can be projected, there is no need to transform subtree
    if (scan.tableSource.getNumberOfFields == usedFields.length) {
      return
    }

    val originTableSource = scan.tableSource.asInstanceOf[ProjectableTableSource[_]]

    val newTableSource = originTableSource.projectFields(usedFields)

    val newScan = new BatchTableSourceScan(
      scan.getCluster,
      scan.getTraitSet,
      scan.getTable,
      newTableSource.asInstanceOf[BatchTableSource[_]])

    val (newProjectExprs, newConditionExpr) = rewriteCalcExprs(calc, usedFields)

    // if project merely returns its input and doesn't exist filter, remove datasetCalc nodes
    val newProjectExprsList = newProjectExprs.asJava
    if (RexUtil.isIdentity(newProjectExprsList, newScan.getRowType)
      && !newConditionExpr.isDefined) {
      call.transformTo(newScan)
    } else {
      val newCalcProgram = RexProgram.create(
        newScan.getRowType,
        newProjectExprsList,
        newConditionExpr.getOrElse(null),
        calc.calcProgram.getOutputRowType,
        calc.getCluster.getRexBuilder)

      val newCal = new DataSetCalc(calc.getCluster,
        calc.getTraitSet,
        newScan,
        calc.getRowType,
        newCalcProgram,
        description)

      call.transformTo(newCal)
    }
  }
}

object PushProjectIntoBatchTableSourceScanRule {
  val INSTANCE: RelOptRule = new PushProjectIntoBatchTableSourceScanRule
}