package com.emubox.app

import android.app.Service
import android.content.Intent
import android.os.IBinder
import org.json.JSONObject
import java.io.File

/**
 * 独立进程驱动实测服务（android:process=":drivertest"）。
 *
 * 目的：主进程已初始化系统 Vulkan/GPU 状态，fork 子进程再加载 adrenotools
 * 驱动的实测路径会因 GPU 状态冲突崩溃（DRIVER_CRASHED）。本服务由系统
 * 在独立进程中全新启动——该进程未触碰系统 Vulkan，adrenotools 加载
 * turnip/panvk 时 GPU 状态干净（与 vktest 独立 App 进程同构），实测
 * 成功率更高；驱动崩溃只杀 service 进程，主进程无感。
 *
 * 结果经 files/driver_test_result.json 回传，主进程轮询读取。
 */
class DriverTestService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val driverDir = intent?.getStringExtra("driverDir")
        val driverName = intent?.getStringExtra("driverName")
        val tmpDir = intent?.getStringExtra("tmpDir")
        val hookLibDir = intent?.getStringExtra("hookLibDir")
        val resultId = intent?.getStringExtra("resultId")
        val runBenchmark = intent?.getBooleanExtra("runBenchmark", false) ?: false

        if (driverDir == null || driverName == null || tmpDir == null || hookLibDir == null || resultId == null) {
            writeResult(resultId ?: "none", JSONObject().put("success", false)
                .put("errorCode", "BAD_ARGS").put("errorMessage", "missing service extras"))
            stopSelf(startId)
            return START_NOT_STICKY
        }

        // 启动标记：主进程据此区分「驱动加载崩溃（进程被杀）」与「执行超时」
        runCatching { File(filesDir, "driver_test_${resultId}.started").writeText("1") }

        Thread {
            try {
                // 分阶段写盘：采集 → 写 → 跑分 → 写 → 渲染 → 写。
                // 驱动渲染/跑分在独立进程崩溃时，已完成的阶段结果不丢失；
                // 主进程读到部分结果即可展示，不会整体判定失败。
                val collectJson = NativeVulkanBridge.collectDriverAdrenotoolsInProcess(
                    driverDir, driverName, tmpDir, hookLibDir
                )
                var result = JSONObject().put("collect", JSONObject(collectJson))
                writeResult(resultId, result)
                if (runBenchmark) {
                    val benchJson = NativeVulkanBridge.benchmarkDriverAdrenotoolsInProcess(
                        driverDir, driverName, tmpDir, hookLibDir
                    )
                    result = result.put("benchmark", JSONObject(benchJson))
                    writeResult(resultId, result)
                    // 渲染测试（vktest 同款）：驱动加载成功后离屏渲染三角形测 FPS
                    val renderJson = NativeVulkanBridge.renderDriverAdrenotoolsInProcess(
                        driverDir, driverName, tmpDir, hookLibDir
                    )
                    result = result.put("render", JSONObject(renderJson))
                    writeResult(resultId, result)
                }
            } catch (e: Exception) {
                writeResult(
                    resultId,
                    JSONObject()
                        .put("success", false)
                        .put("errorCode", "SERVICE_EXCEPTION")
                        .put("errorMessage", e.message ?: "unknown")
                )
            } finally {
                // 无论结果如何，清除启动标记，避免主进程误判为崩溃
                runCatching { File(filesDir, "driver_test_${resultId}.started").delete() }
                stopSelf(startId)
            }
        }.start()
        return START_NOT_STICKY
    }

    private fun writeResult(resultId: String, json: JSONObject) {
        runCatching {
            val file = File(filesDir, "driver_test_${resultId}.json")
            file.writeText(json.toString())
            // 清理旧结果与启动标记，避免主进程读到过期文件
            filesDir.listFiles()?.filter {
                it.name.startsWith("driver_test_") && it.name != file.name
            }?.forEach { it.delete() }
            File(filesDir, "driver_test_${resultId}.started").delete()
        }
    }

    companion object {
        /** 结果文件绝对路径（主进程同一 filesDir，可共享读取）。 */
        fun resultFile(context: android.content.Context, resultId: String): File =
            File(context.filesDir, "driver_test_${resultId}.json")
    }
}
