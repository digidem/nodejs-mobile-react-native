package com.janeasystems.rn_nodejs_mobile

import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.modules.core.RCTNativeAppEventEmitter
import com.facebook.react.module.annotations.ReactModule
import com.facebook.react.bridge.WritableMap
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.ReadableType
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.LifecycleEventListener
import android.util.Log
import android.content.Context
import android.content.res.AssetManager
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.SharedPreferences
import android.system.Os
import android.system.ErrnoException
import java.io.*
import java.util.*
import java.util.concurrent.Semaphore

class RNNodeJsMobileModule(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext), LifecycleEventListener {

    companion object {
        private const val TAG = "NODEJS-RN"
        private const val NODEJS_PROJECT_DIR = "nodejs-project"
        private const val NODEJS_BUILTIN_MODULES = "nodejs-builtin_modules"
        private const val TRASH_DIR = "nodejs-project-trash"
        private const val SHARED_PREFS = "NODEJS_MOBILE_PREFS"
        private const val LAST_UPDATED_TIME = "NODEJS_MOBILE_APK_LastUpdateTime"
        private const val BUILTIN_NATIVE_ASSETS_PREFIX = "nodejs-native-assets-"
        private const val SYSTEM_CHANNEL = "_SYSTEM_"

        private lateinit var trashDirPath: String
        private lateinit var filesDirPath: String
        private lateinit var nodeJsProjectPath: String
        private lateinit var builtinModulesPath: String
        private lateinit var nativeAssetsPath: String

        private var lastUpdateTime: Long = 1
        private var previousLastUpdateTime: Long = 0
        private val initSemaphore = Semaphore(1)
        private var initCompleted = false

        private lateinit var assetManager: AssetManager

        // Flag to indicate if node is ready to receive app events.
        private var nodeIsReadyForAppEvents = false

        init {
            System.loadLibrary("nodejs-mobile-react-native-native-lib")
            System.loadLibrary("node")
        }

        // To store the instance when node is started.
        var instance: RNNodeJsMobileModule? = null
            private set

        // We just want one instance of node running in the background.
        var startedNodeAlready = false
            private set

        fun sendMessageToApplication(channelName: String, msg: String) {
            if (channelName == SYSTEM_CHANNEL) {
                // If it's a system channel call, handle it in the plugin native side.
                handleAppChannelMessage(msg)
            } else {
                // Otherwise, send it to React Native.
                sendMessageBackToReact(channelName, msg)
            }
        }

        fun handleAppChannelMessage(msg: String) {
            if (msg == "ready-for-app-events") {
                nodeIsReadyForAppEvents = true
            }
        }

        // Called from JNI when node sends a message through the bridge.
        fun sendMessageBackToReact(channelName: String, msg: String) {
            instance?.let { moduleInstance ->
                Thread {
                    val params = Arguments.createMap().apply {
                        putString("channelName", channelName)
                        putString("message", msg)
                    }
                    moduleInstance.sendEvent("nodejs-mobile-react-native-message", params)
                }.start()
            }
        }

        // Recursively deletes a folder
        private fun deleteFolderRecursively(file: File): Boolean {
            return try {
                var res = true
                file.listFiles()?.forEach { childFile ->
                    res = if (childFile.isDirectory) {
                        res and deleteFolderRecursively(childFile)
                    } else {
                        res and childFile.delete()
                    }
                }
                res and file.delete()
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }

        // Recursively copies contents of a folder in assets to a path
        private fun copyAssetFolder(fromAssetPath: String, toPath: String) {
            val files = assetManager.list(fromAssetPath) ?: return

            if (files.isEmpty()) {
                // If it's a file, it won't have any assets "inside" it.
                copyAsset(fromAssetPath, toPath)
            } else {
                File(toPath).mkdirs()
                files.forEach { file ->
                    copyAssetFolder("$fromAssetPath/$file", "$toPath/$file")
                }
            }
        }

        private fun copyAsset(fromAssetPath: String, toPath: String) {
            var inputStream: InputStream? = null
            var outputStream: OutputStream? = null
            try {
                inputStream = assetManager.open(fromAssetPath)
                File(toPath).createNewFile()
                outputStream = FileOutputStream(toPath)
                copyFile(inputStream, outputStream)
            } catch (e: IOException) {
                e.printStackTrace()
            } finally {
                inputStream?.close()
                outputStream?.flush()
                outputStream?.close()
            }
        }

        // Copy file from an input stream to an output stream
        private fun copyFile(inputStream: InputStream, outputStream: OutputStream) {
            val buffer = ByteArray(1024)
            var read: Int
            while (inputStream.read(buffer).also { read = it } != -1) {
                outputStream.write(buffer, 0, read)
            }
        }
    }

    private val reactContext: ReactApplicationContext = reactContext

    init {
        this.reactContext.addLifecycleEventListener(this)
        filesDirPath = this.reactContext.filesDir.absolutePath

        // The paths where we expect the node project assets to be at runtime.
        nodeJsProjectPath = "$filesDirPath/$NODEJS_PROJECT_DIR"
        builtinModulesPath = "$filesDirPath/$NODEJS_BUILTIN_MODULES"
        trashDirPath = "$filesDirPath/$TRASH_DIR"
        nativeAssetsPath = "$BUILTIN_NATIVE_ASSETS_PREFIX${getCurrentABIName()}"

        // Sets the TMPDIR environment to the cacheDir, to be used in Node as os.tmpdir
        try {
            Os.setenv("TMPDIR", this.reactContext.cacheDir.absolutePath, true)
        } catch (e: ErrnoException) {
            e.printStackTrace()
        }

        // Register the filesDir as the Node data dir.
        registerNodeDataDirPath(filesDirPath)

        asyncInit()
    }

    private fun asyncInit() {
        if (wasAPKUpdated()) {
            try {
                initSemaphore.acquire()
                Thread {
                    emptyTrash()
                    try {
                        copyNodeJsAssets()
                        initCompleted = true
                    } catch (e: IOException) {
                        throw RuntimeException("Node assets copy failed", e)
                    }
                    initSemaphore.release()
                    emptyTrash()
                }.start()
            } catch (ie: InterruptedException) {
                initSemaphore.release()
                ie.printStackTrace()
            }
        } else {
            initCompleted = true
        }
    }

    override fun getName(): String = "RNNodeJsMobile"

    // Extracts the option to redirect stdout and stderr to logcat
    private fun extractRedirectOutputToLogcatOption(options: ReadableMap?): Boolean {
        val optionName = "redirectOutputToLogcat"
        return if (options?.hasKey(optionName) == true &&
                   !options.isNull(optionName) &&
                   options.getType(optionName) == ReadableType.Boolean) {
            options.getBoolean(optionName)
        } else {
            // By default, we redirect the process' stdout and stderr to show in logcat
            true
        }
    }

    @ReactMethod
    fun startNodeWithScript(script: String, options: ReadableMap?) {
        // A New module instance may have been created due to hot reload.
        instance = this
        if (!startedNodeAlready) {
            startedNodeAlready = true

            val redirectOutputToLogcat = extractRedirectOutputToLogcatOption(options)

            Thread {
                waitForInit()
                startNodeWithArguments(
                    arrayOf("node", "-e", script),
                    "$nodeJsProjectPath:$builtinModulesPath",
                    redirectOutputToLogcat
                )
            }.start()
        }
    }

    @ReactMethod
    fun startNodeProject(mainFileName: String, options: ReadableMap?) {
        // A New module instance may have been created due to hot reload.
        instance = this
        if (!startedNodeAlready) {
            startedNodeAlready = true

            val redirectOutputToLogcat = extractRedirectOutputToLogcatOption(options)

            Thread {
                waitForInit()
                startNodeWithArguments(
                    arrayOf("node", "$nodeJsProjectPath/$mainFileName"),
                    "$nodeJsProjectPath:$builtinModulesPath",
                    redirectOutputToLogcat
                )
            }.start()
        }
    }

    @ReactMethod
    fun startNodeProjectWithArgs(input: String, options: ReadableMap?) {
        // A New module instance may have been created due to hot reload.
        instance = this
        if (!startedNodeAlready) {
            startedNodeAlready = true

            val args = input.split(" ").toMutableList()
            val absoluteScriptPath = "$nodeJsProjectPath/${args[0]}"

            // Remove script file name from arguments list
            args.removeAt(0)

            val command = mutableListOf<String>().apply {
                add("node")
                add(absoluteScriptPath)
                addAll(args)
            }

            val redirectOutputToLogcat = extractRedirectOutputToLogcatOption(options)

            Thread {
                waitForInit()
                startNodeWithArguments(
                    command.toTypedArray(),
                    "$nodeJsProjectPath:$builtinModulesPath",
                    redirectOutputToLogcat
                )
            }.start()
        }
    }

    @ReactMethod
    fun sendMessage(channel: String, msg: String) {
        sendMessageToNodeChannel(channel, msg)
    }

    // Sends an event through the App Event Emitter.
    private fun sendEvent(eventName: String, params: WritableMap?) {
        reactContext
            .getJSModule(RCTNativeAppEventEmitter::class.java)
            .emit(eventName, params)
    }

    override fun onHostPause() {
        if (nodeIsReadyForAppEvents) {
            sendMessageToNodeChannel(SYSTEM_CHANNEL, "pause")
        }
    }

    override fun onHostResume() {
        if (nodeIsReadyForAppEvents) {
            sendMessageToNodeChannel(SYSTEM_CHANNEL, "resume")
        }
    }

    override fun onHostDestroy() {
        // Activity `onDestroy`
    }

    external fun registerNodeDataDirPath(dataDir: String)
    external fun getCurrentABIName(): String
    external fun startNodeWithArguments(
        arguments: Array<String>,
        modulesPath: String,
        redirectOutputToLogcat: Boolean
    ): Int
    external fun sendMessageToNodeChannel(channelName: String, msg: String)

    private fun waitForInit() {
        if (!initCompleted) {
            try {
                initSemaphore.acquire()
                initSemaphore.release()
            } catch (ie: InterruptedException) {
                initSemaphore.release()
                ie.printStackTrace()
            }
        }
    }

    private fun wasAPKUpdated(): Boolean {
        val prefs = reactContext.getSharedPreferences(SHARED_PREFS, Context.MODE_PRIVATE)
        previousLastUpdateTime = prefs.getLong(LAST_UPDATED_TIME, 0)

        try {
            val packageInfo = reactContext.packageManager.getPackageInfo(reactContext.packageName, 0)
            lastUpdateTime = packageInfo.lastUpdateTime
        } catch (e: PackageManager.NameNotFoundException) {
            e.printStackTrace()
        }
        return lastUpdateTime != previousLastUpdateTime
    }

    private fun saveLastUpdateTime() {
        val prefs = reactContext.getSharedPreferences(SHARED_PREFS, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putLong(LAST_UPDATED_TIME, lastUpdateTime)
            apply()
        }
    }

    private fun emptyTrash() {
        val trash = File(trashDirPath)
        if (trash.exists()) {
            deleteFolderRecursively(trash)
        }
    }

    private fun copyNativeAssetsFrom(): Boolean {
        return try {
            // Load the additional asset folder and files lists
            val nativeDirs = readFileFromAssets("$nativeAssetsPath/dir.list")
            val nativeFiles = readFileFromAssets("$nativeAssetsPath/file.list")

            // Copy additional asset files to project working folder
            if (nativeFiles.isNotEmpty()) {
                Log.v(TAG, "Building folder hierarchy for $nativeAssetsPath")
                nativeDirs.forEach { dir ->
                    File("$nodeJsProjectPath/$dir").mkdirs()
                }
                Log.v(TAG, "Copying assets using file list for $nativeAssetsPath")
                nativeFiles.forEach { file ->
                    val src = "$nativeAssetsPath/$file"
                    val dest = "$nodeJsProjectPath/$file"
                    copyAsset(src, dest)
                }
            } else {
                Log.v(TAG, "No assets to copy from $nativeAssetsPath")
            }
            true
        } catch (e: IOException) {
            e.printStackTrace()
            false
        }
    }

    private fun copyNodeJsAssets() {
        assetManager = reactApplicationContext.assets

        // If a previous project folder is present, move it to the trash.
        val nodeDirReference = File(nodeJsProjectPath)
        if (nodeDirReference.exists()) {
            val trash = File(trashDirPath)
            nodeDirReference.renameTo(trash)
        }

        // Load the nodejs project's folder and file lists.
        val dirs = readFileFromAssets("dir.list")
        val files = readFileFromAssets("file.list")

        // Copy the nodejs project files to the application's data path.
        if (dirs.isNotEmpty() && files.isNotEmpty()) {
            Log.d(TAG, "Node assets copy using pre-built lists")
            dirs.forEach { dir ->
                File("$filesDirPath/$dir").mkdirs()
            }

            files.forEach { file ->
                val src = file
                val dest = "$filesDirPath/$file"
                copyAsset(src, dest)
            }
        } else {
            Log.d(TAG, "Node assets copy enumerating APK assets")
            copyAssetFolder(NODEJS_PROJECT_DIR, nodeJsProjectPath)
        }

        copyNativeAssetsFrom()

        // Do the builtin-modules copy too.
        // If a previous built-in modules folder is present, delete it.
        val modulesDirReference = File(builtinModulesPath)
        if (modulesDirReference.exists()) {
            deleteFolderRecursively(modulesDirReference)
        }

        // Copy the nodejs built-in modules to the application's data path.
        copyAssetFolder("builtin_modules", builtinModulesPath)

        saveLastUpdateTime()
        Log.d(TAG, "Node assets copy completed successfully")
    }

    private fun readFileFromAssets(filename: String): ArrayList<String> {
        val lines = ArrayList<String>()
        try {
            BufferedReader(InputStreamReader(assetManager.open(filename))).use { reader ->
                var line = reader.readLine()
                while (line != null) {
                    lines.add(line)
                    line = reader.readLine()
                }
            }
        } catch (e: FileNotFoundException) {
            Log.d(TAG, "File not found: $filename")
        } catch (e: IOException) {
            e.printStackTrace()
        }
        return lines
    }
}
