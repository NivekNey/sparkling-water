/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.h2o.sparkling.ml.models

import ai.h2o.sparkling.ml.params.HasInputColsOnMOJO
import org.apache.spark.expose.Logging
import org.apache.spark.ml.param.ParamMap
import org.apache.spark.sql.expressions.UserDefinedFunction
import org.apache.spark.sql.{DataFrame, Dataset}
import org.apache.spark.sql.types.{StructField, StructType}

abstract class H2OFeatureMOJOModel
  extends H2OMOJOModel
  with H2OFeatureEstimatorBase
  with SpecificMOJOParameters
  with HasMojo
  with H2OMOJOWritable
  with H2OMOJOFlattenedInput
  with Logging {
  override def copy(extra: ParamMap): H2OFeatureMOJOModel = defaultCopy(extra)

  override protected def outputColumnName: String = getClass.getSimpleName + "_temporary"

  protected def mojoUDF: UserDefinedFunction

  override def transform(dataset: Dataset[_]): DataFrame = {
    val outputDF = applyPredictionUdf(dataset, _ => mojoUDF)
    outputDF
      .select("*", s"$outputColumnName.*")
      .drop(outputColumnName)
  }
}
