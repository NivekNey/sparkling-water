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

package org.apache.spark.h2o.backends.internal

import java.io.File

import ai.h2o.sparkling.backend.external.ExternalBackendConf
import ai.h2o.sparkling.backend.utils.RestApiUtils
import ai.h2o.sparkling.backend.{NodeDesc, SparklingBackend}
import ai.h2o.sparkling.utils.SparkSessionUtils
import ai.h2o.sparkling.{H2OConf, H2OContext}
import org.apache.hadoop.conf.Configuration
import org.apache.spark.expose.Utils
import org.apache.spark.h2o.backends.internal.InternalH2OBackend._
import org.apache.spark.internal.Logging
import org.apache.spark.rpc.RpcEndpointRef
import org.apache.spark.scheduler.{SparkListener, SparkListenerExecutorAdded}
import org.apache.spark.util.RpcUtils
import org.apache.spark.{SparkContext, SparkEnv}
import water.hive.DelegationTokenRefresher
import water.util.Log
import water.{H2O, H2OStarter}

class InternalH2OBackend(@transient val hc: H2OContext) extends SparklingBackend with Logging {

  override def backendUIInfo: Seq[(String, String)] = Seq()

  override def startH2OCluster(conf: H2OConf): Unit = {
    logInfo("Starting the H2O cluster inside Spark.")
    if (hc.sparkContext.isLocal) {
      startSingleH2OWorker(conf)
    } else {
      val endpoints = registerEndpoints(hc)
      val workerNodes = startH2OWorkers(endpoints, conf)
      distributeFlatFile(endpoints, conf, workerNodes)
      waitForClusterSize(endpoints, conf, workerNodes.length)
      lockCloud(endpoints)
      val leaderNodeDesc = getLeaderNode(endpoints, conf)
      tearDownEndpoints(endpoints)
      registerNewExecutorListener(hc)
      conf.set(ExternalBackendConf.PROP_EXTERNAL_CLUSTER_REPRESENTATIVE._1, leaderNodeDesc.ipPort())
    }
  }

  override def epilog: String = ""
}

object InternalH2OBackend extends InternalBackendUtils {

  private def getLeaderNode(endpoints: Array[RpcEndpointRef], conf: H2OConf): NodeDesc = {
    val askTimeout = RpcUtils.askRpcTimeout(conf.sparkConf)
    endpoints.flatMap { ref =>
      val future = ref.ask[Option[NodeDesc]](GetLeaderNodeMsg)
      askTimeout.awaitResult(future)
    }.head
  }

  private def waitForClusterSize(endpoints: Array[RpcEndpointRef], conf: H2OConf, expectedSize: Int): Unit = {
    val start = System.currentTimeMillis()
    val timeout = conf.cloudTimeout
    while (System.currentTimeMillis() - start < timeout) {
      if (isClusterOfExpectedSize(endpoints, conf, expectedSize)) {
        return
      }
      try {
        Thread.sleep(100)
      } catch {
        case _: InterruptedException =>
      }
    }
  }

  private def lockCloud(endpoints: Array[RpcEndpointRef]): Unit = {
    endpoints.head.send(LockClusterMsg)
  }

  private def isClusterOfExpectedSize(endpoints: Array[RpcEndpointRef], conf: H2OConf, expectedSize: Int): Boolean = {
    val askTimeout = RpcUtils.askRpcTimeout(conf.sparkConf)
    !endpoints
      .map { ref =>
        val future = ref.ask[Int](CheckClusterSizeMsg)
        val clusterSize = askTimeout.awaitResult(future)
        clusterSize
      }
      .exists(_ != expectedSize)
  }

  override def checkAndUpdateConf(conf: H2OConf): H2OConf = {
    super.checkAndUpdateConf(conf)

    // Always wait for the local node - H2O node
    logWarning(
      s"Increasing 'spark.locality.wait' to value 0 (Infinitive) as we need to ensure we run on the nodes with H2O")
    conf.set("spark.locality.wait", "0")

    conf.getOption("spark.executor.instances").foreach(v => conf.set("spark.ext.h2o.cluster.size", v))

    if (!conf.contains("spark.scheduler.minRegisteredResourcesRatio") && !SparkSessionUtils.active.sparkContext.isLocal) {
      logWarning(
        "The property 'spark.scheduler.minRegisteredResourcesRatio' is not specified!\n" +
          "We recommend to pass `--conf spark.scheduler.minRegisteredResourcesRatio=1`")
      // Setup the property but at this point it does not make good sense
      conf.set("spark.scheduler.minRegisteredResourcesRatio", "1")
    }

    if (conf.cloudName.isEmpty) {
      conf.setCloudName("sparkling-water-" + System.getProperty("user.name", "cluster") + "_" + conf.sparkConf.getAppId)
    }

    if (conf.hdfsConf.isEmpty) {
      conf.setHdfsConf(SparkContext.getOrCreate().hadoopConfiguration)
    }

    checkUnsupportedSparkOptions(InternalH2OBackend.UNSUPPORTED_SPARK_OPTIONS, conf)
    distributeFiles(conf, SparkSessionUtils.active.sparkContext)

    conf
  }

  val UNSUPPORTED_SPARK_OPTIONS: Seq[(String, String)] =
    Seq(("spark.dynamicAllocation.enabled", "true"), ("spark.speculation", "true"))

  /**
    * Used in local mode where we start directly one H2O worker node
    * without additional client
    */
  private def startSingleH2OWorker(conf: H2OConf): Unit = {
    val args = getH2OWorkerAsClientArgs(conf)
    val launcherArgs = toH2OArgs(args)
    initializeH2OKerberizedHiveSupport(conf)
    H2OStarter.start(launcherArgs, true)
    conf.set(ExternalBackendConf.PROP_EXTERNAL_CLUSTER_REPRESENTATIVE._1, H2O.getIpPortString)
  }

  def startH2OWorker(conf: H2OConf): NodeDesc = {
    val args = getH2OWorkerArgs(conf)
    val launcherArgs = toH2OArgs(args)
    initializeH2OKerberizedHiveSupport(conf)
    H2OStarter.start(launcherArgs, true)
    NodeDesc(SparkEnv.get.executorId, H2O.SELF_ADDRESS.getHostAddress, H2O.API_PORT)
  }

  private def registerNewExecutorListener(hc: H2OContext): Unit = {
    if (!hc.sparkContext.master.startsWith("local-cluster[") && hc.getConf.isClusterTopologyListenerEnabled) {
      hc.sparkContext.addSparkListener(new SparkListener {
        override def onExecutorAdded(executorAdded: SparkListenerExecutorAdded): Unit = {
          log.warn("New spark executor joined the cloud, however it won't be used for the H2O computations.")
        }
      })
    }
  }

  private def tearDownEndpoints(endpoints: Array[RpcEndpointRef]): Unit = endpoints.foreach(_.send(StopEndpointMsg))

  private def registerEndpoints(hc: H2OContext): Array[RpcEndpointRef] = {
    val endpoints = new SpreadRDDBuilder(hc, guessTotalExecutorSize(SparkSessionUtils.active.sparkContext)).build()
    val conf = hc.getConf
    val endpointsFinal = if (conf.numH2OWorkers.isDefined && !conf.extraClusterNodes) {
      endpoints.take(conf.numH2OWorkers.get)
    } else {
      endpoints
    }
    endpointsFinal.map(ref => SparkEnv.get.rpcEnv.setupEndpointRef(ref.address, ref.name))
  }

  private def startH2OWorkers(endpoints: Array[RpcEndpointRef], conf: H2OConf): Array[NodeDesc] = {
    val askTimeout = RpcUtils.askRpcTimeout(conf.sparkConf)
    endpoints.map { ref =>
      val future = ref.ask[NodeDesc](StartH2OWorkersMsg(conf))
      val node = askTimeout.awaitResult(future)
      Log.info(s"H2O's worker node $node started.")
      node
    }
  }

  private def distributeFlatFile(endpoints: Array[RpcEndpointRef], conf: H2OConf, nodes: Array[NodeDesc]): Unit = {
    Log.info(s"Distributing worker nodes locations: ${nodes.mkString(",")}")
    endpoints.foreach {
      _.send(FlatFileMsg(nodes, conf.internalPortOffset))
    }
  }

  private def initializeH2OKerberizedHiveSupport(conf: H2OConf): Unit = {
    if (conf.isKerberizedHiveEnabled) {
      val configuration = new Configuration()
      conf.hiveHost.foreach(configuration.set(DelegationTokenRefresher.H2O_HIVE_HOST, _))
      conf.hivePrincipal.foreach(configuration.set(DelegationTokenRefresher.H2O_HIVE_PRINCIPAL, _))
      conf.hiveJdbcUrlPattern.foreach(configuration.set(DelegationTokenRefresher.H2O_HIVE_JDBC_URL_PATTERN, _))
      conf.hiveToken.foreach(configuration.set(DelegationTokenRefresher.H2O_HIVE_TOKEN, _))

      val sparkTmpDir = new File(Utils.getLocalDir(SparkEnv.get.conf))

      DelegationTokenRefresher.setup(configuration, sparkTmpDir.getAbsolutePath)
    }
  }
}
