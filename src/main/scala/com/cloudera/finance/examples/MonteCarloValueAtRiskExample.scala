/**
 * Copyright (c) 2015, Cloudera, Inc. All Rights Reserved.
 *
 * Cloudera, Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"). You may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * This software is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for
 * the specific language governing permissions and limitations under the
 * License.
 */

package com.cloudera.finance.examples

import com.cloudera.finance.risk.LinearInstrumentReturnsModel
import com.cloudera.finance.risk.ValueAtRisk._
import com.cloudera.finance.Util._

import org.apache.commons.math3.stat.correlation.Covariance

import org.apache.spark.{SparkConf, SparkContext}
import org.apache.commons.math3.stat.regression.OLSMultipleLinearRegression
import com.cloudera.finance.SerializableMultivariateNormalDistribution

object MonteCarloValueAtRiskExample {
  def main(args: Array[String]): Unit = {
    // Read parameters
    val numTrials = if (args.length > 0) args(0).toInt else 10000
    val parallelism = if (args.length > 1) args(1).toInt else 10
    val factorsDir = if (args.length > 2) args(2) else "factors/"
    val instrumentsDir = if (args.length > 3) args(3) else "instruments/"

    // Load factors
    val factorHistories = ExampleUtil.readHistories(factorsDir)
    val factorReturns = factorHistories.differences(10)

    // Load instruments
    val instrumentHistories = ExampleUtil.readHistories(instrumentsDir)
    val instrumentReturns = instrumentHistories.differences(10)

    // Initialize Spark
    val conf = new SparkConf().setMaster("local").setAppName("Historical VaR")
    val sc = new SparkContext(conf)

    // Fit factor return -> instrument return predictive models
    val factorObservations = factorReturns.observations()
    val linearModels = instrumentReturns.data.map { instrumentReturns =>
      val regression = new OLSMultipleLinearRegression()
      regression.newSampleData(instrumentReturns, factorObservations)
      regression.estimateRegressionParameters()
    }
    val instrumentReturnsModel = new LinearInstrumentReturnsModel(arrsToMat(linearModels))

    // Generate an RDD of simulations
    val factorCov = new Covariance(factorObservations).getCovarianceMatrix().getData()
    val factorMeans = factorHistories.data.map(factor => factor.sum / factor.size).toArray
    val factorsDist = new SerializableMultivariateNormalDistribution(factorMeans, factorCov)
    val returns = simulationReturns(0L, factorsDist, numTrials, parallelism, sc,
      instrumentReturnsModel)
    returns.cache()

    // Calculate VaR and expected shortfall
    val pValues = Array(.01, .03, .05)
    val valueAtRisks = valueAtRisk(returns, pValues)
    println(s"Value at risk at ${pValues.mkString(",")}: ${valueAtRisks.mkString(",")}")

    val expectedShortfalls = expectedShortfall(returns, pValues)
    println(s"Expected shortfall at ${pValues.mkString(",")}: ${expectedShortfalls.mkString(",")}")
  }
}
