#include <jni.h>
#include <string>
#include <cstdlib>
#include <pthread.h>
#include <unistd.h>
#include <android/log.h>
#include <chrono>

#include "node.h"
#include "rn-bridge.h"

// cache JavaVM for thread attachment and JNI references for performance
static JavaVM* cached_jvm = nullptr;
static jclass cached_class = nullptr;
static jmethodID cached_method = nullptr;

extern "C"
JNIEXPORT void JNICALL
Java_com_nodejsmobile_reactnative_RNNodeJsMobileModule_sendMessageToNodeChannel(
        JNIEnv *env,
        jobject /* this */,
        jstring channelName,
        jstring msg) {
    const char* nativeChannelName = env->GetStringUTFChars(channelName, 0);
    const char* nativeMessage = env->GetStringUTFChars(msg, 0);
    rn_bridge_notify(nativeChannelName, nativeMessage);
    env->ReleaseStringUTFChars(channelName,nativeChannelName);
    env->ReleaseStringUTFChars(msg,nativeMessage);
}

extern "C" int callintoNode(int argc, char *argv[])
{
    const int exit_code = node::Start(argc,argv);
    return exit_code;
}

#if defined(__arm__)
    #define CURRENT_ABI_NAME "armeabi-v7a"
#elif defined(__aarch64__)
    #define CURRENT_ABI_NAME "arm64-v8a"
#elif defined(__i386__)
    #define CURRENT_ABI_NAME "x86"
#elif defined(__x86_64__)
    #define CURRENT_ABI_NAME "x86_64"
#else
    #error "Trying to compile for an unknown ABI."
#endif

extern "C"
JNIEXPORT jstring JNICALL
Java_com_nodejsmobile_reactnative_RNNodeJsMobileModule_getCurrentABIName(
    JNIEnv *env,
    jobject /* this */) {
    return env->NewStringUTF(CURRENT_ABI_NAME);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_nodejsmobile_reactnative_RNNodeJsMobileModule_registerNodeDataDirPath(
    JNIEnv *env,
    jobject /* this */,
    jstring dataDir) {
  const char* nativeDataDir = env->GetStringUTFChars(dataDir, 0);
  rn_register_node_data_dir_path(nativeDataDir);
  env->ReleaseStringUTFChars(dataDir, nativeDataDir);
}

#define APPNAME "RNBRIDGE"

void rcv_message(const char* channel_name, const char* msg) {
  auto start_time = std::chrono::high_resolution_clock::now();
  
  // Log thread information
  pthread_t current_thread = pthread_self();
  
  if (!cached_jvm || !cached_class || !cached_method) {
    __android_log_print(ANDROID_LOG_WARN, "RN_BRIDGE_PERF", "rcv_message: Invalid jvm/class/method pointers on thread %lu", (unsigned long)current_thread);
    return;
  }
  
  __android_log_print(ANDROID_LOG_INFO, "RN_BRIDGE_PERF", "rcv_message: Called on thread %lu", (unsigned long)current_thread);
  
  auto after_checks = std::chrono::high_resolution_clock::now();
  
  // Attach current thread to get valid JNIEnv
  JNIEnv* env = nullptr;
  bool attached = false;
  jint attach_result = cached_jvm->GetEnv((void**)&env, JNI_VERSION_1_6);
  
  if (attach_result == JNI_EDETACHED) {
    // Thread is not attached, attach it
    if (cached_jvm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
      attached = true;
      __android_log_print(ANDROID_LOG_INFO, "RN_BRIDGE_PERF", "Successfully attached thread %lu", (unsigned long)current_thread);
    } else {
      __android_log_print(ANDROID_LOG_ERROR, "RN_BRIDGE_PERF", "Failed to attach thread %lu", (unsigned long)current_thread);
      return;
    }
  } else if (attach_result != JNI_OK) {
    __android_log_print(ANDROID_LOG_ERROR, "RN_BRIDGE_PERF", "GetEnv failed on thread %lu", (unsigned long)current_thread);
    return;
  }
  
  auto after_thread_attach = std::chrono::high_resolution_clock::now();
  
  jstring java_channel_name = env->NewStringUTF(channel_name);
  jstring java_msg = env->NewStringUTF(msg);
  
  auto after_string_creation = std::chrono::high_resolution_clock::now();
  
  env->CallStaticVoidMethod(cached_class, cached_method, java_channel_name, java_msg);
  
  auto after_jni_call = std::chrono::high_resolution_clock::now();
  
  env->DeleteLocalRef(java_channel_name);
  env->DeleteLocalRef(java_msg);
  
  // Detach thread if we attached it (for native threads)
  if (attached) {
    cached_jvm->DetachCurrentThread();
  }
  
  auto end_time = std::chrono::high_resolution_clock::now();
  
  // Log timing breakdown
  auto total_us = std::chrono::duration_cast<std::chrono::microseconds>(end_time - start_time).count();
  auto checks_us = std::chrono::duration_cast<std::chrono::microseconds>(after_checks - start_time).count();
  auto attach_us = std::chrono::duration_cast<std::chrono::microseconds>(after_thread_attach - after_checks).count();
  auto strings_us = std::chrono::duration_cast<std::chrono::microseconds>(after_string_creation - after_thread_attach).count();
  auto jni_call_us = std::chrono::duration_cast<std::chrono::microseconds>(after_jni_call - after_string_creation).count();
  auto cleanup_us = std::chrono::duration_cast<std::chrono::microseconds>(end_time - after_jni_call).count();
  
  __android_log_print(ANDROID_LOG_INFO, "RN_BRIDGE_PERF", 
    "rcv_message timing: total=%ldμs checks=%ldμs attach=%ldμs strings=%ldμs jni_call=%ldμs cleanup=%ldμs attached=%s", 
    total_us, checks_us, attach_us, strings_us, jni_call_us, cleanup_us, attached ? "true" : "false");
}

// Start threads to redirect stdout and stderr to logcat.
int pipe_stdout[2];
int pipe_stderr[2];
pthread_t thread_stdout;
pthread_t thread_stderr;
const char *ADBTAG = "NODEJS-MOBILE";

void *thread_stderr_func(void*) {
    ssize_t redirect_size;
    char buf[2048];
    while((redirect_size = read(pipe_stderr[0], buf, sizeof buf - 1)) > 0) {
        //__android_log will add a new line anyway.
        if(buf[redirect_size - 1] == '\n')
            --redirect_size;
        buf[redirect_size] = 0;
        __android_log_write(ANDROID_LOG_ERROR, ADBTAG, buf);
    }
    return 0;
}

void *thread_stdout_func(void*) {
    ssize_t redirect_size;
    char buf[2048];
    while((redirect_size = read(pipe_stdout[0], buf, sizeof buf - 1)) > 0) {
        //__android_log will add a new line anyway.
        if(buf[redirect_size - 1] == '\n')
            --redirect_size;
        buf[redirect_size] = 0;
        __android_log_write(ANDROID_LOG_INFO, ADBTAG, buf);
    }
    return 0;
}

int start_redirecting_stdout_stderr() {
    //set stdout as unbuffered.
    setvbuf(stdout, 0, _IONBF, 0);
    pipe(pipe_stdout);
    dup2(pipe_stdout[1], STDOUT_FILENO);

    //set stderr as unbuffered.
    setvbuf(stderr, 0, _IONBF, 0);
    pipe(pipe_stderr);    
    dup2(pipe_stderr[1], STDERR_FILENO);

    if(pthread_create(&thread_stdout, 0, thread_stdout_func, 0) == -1)
        return -1;
    pthread_detach(thread_stdout);

    if(pthread_create(&thread_stderr, 0, thread_stderr_func, 0) == -1)
        return -1;
    pthread_detach(thread_stderr);

    return 0;
}

//node's libUV requires all arguments being on contiguous memory.
extern "C" jint JNICALL
Java_com_nodejsmobile_reactnative_RNNodeJsMobileModule_startNodeWithArguments(
        JNIEnv *env,
        jobject /* this */,
        jobjectArray arguments,
        jstring modulesPath,
        jboolean option_redirectOutputToLogcat) {

    //Set the builtin_modules path to NODE_PATH.
    const char* path_path = env->GetStringUTFChars(modulesPath, 0);
    setenv("NODE_PATH", path_path, 1);
    env->ReleaseStringUTFChars(modulesPath, path_path);

    //argc
    jsize argument_count = env->GetArrayLength(arguments);

    // Store references for proper cleanup
    jobject* object_refs = new jobject[argument_count];
    const char** string_refs = new const char*[argument_count];
    
    //Compute byte size need for all arguments in contiguous memory.
    int c_arguments_size = 0;
    for (int i = 0; i < argument_count ; i++) {
        object_refs[i] = env->GetObjectArrayElement(arguments, i);
        string_refs[i] = env->GetStringUTFChars((jstring)object_refs[i], 0);
        c_arguments_size += strlen(string_refs[i]);
        c_arguments_size++; // for '\0'
    }

    //Stores arguments in contiguous memory.
    char* args_buffer=(char*)calloc(c_arguments_size, sizeof(char));

    //argv to pass into node.
    char* argv[argument_count];

    //To iterate through the expected start position of each argument in args_buffer.
    char* current_args_position=args_buffer;

    //Populate the args_buffer and argv.
    for (int i = 0; i < argument_count ; i++)
    {
        const char* current_argument = string_refs[i];

        //Copy current argument to its expected position in args_buffer
        strncpy(current_args_position, current_argument, strlen(current_argument));

        //Save current argument start position in argv
        argv[i] = current_args_position;

        //Increment to the next argument's expected position.
        current_args_position += strlen(current_args_position)+1;
    }

    rn_register_bridge_cb(&rcv_message);

    // Store JavaVM for thread attachment
    if (!cached_jvm) {
        if (env->GetJavaVM(&cached_jvm) != JNI_OK) {
            __android_log_print(ANDROID_LOG_ERROR, "RN_BRIDGE_PERF", "Failed to get JavaVM");
            return jint(-1);
        }
    }
    
    // Log thread information for debugging
    pthread_t current_thread = pthread_self();
    __android_log_print(ANDROID_LOG_INFO, "RN_BRIDGE_PERF", "startNodeWithArguments thread: %lu", (unsigned long)current_thread);

    // Initialize cached JNI references for performance
    if (!cached_class) {
        jclass local_class = env->FindClass("com/nodejsmobile/reactnative/RNNodeJsMobileModule");
        if (local_class) {
            cached_class = (jclass)env->NewGlobalRef(local_class);
            cached_method = env->GetStaticMethodID(cached_class, "sendMessageToApplication", "(Ljava/lang/String;Ljava/lang/String;)V");
            env->DeleteLocalRef(local_class);
            __android_log_print(ANDROID_LOG_INFO, "RN_BRIDGE_PERF", "Cached JNI references initialized on thread: %lu", (unsigned long)current_thread);
        } else {
            __android_log_print(ANDROID_LOG_ERROR, "RN_BRIDGE_PERF", "Failed to find RNNodeJsMobileModule class");
        }
    }

    //Start threads to show stdout and stderr in logcat.
    if (option_redirectOutputToLogcat) {
        if (start_redirecting_stdout_stderr()==-1) {
            __android_log_write(ANDROID_LOG_ERROR, ADBTAG, "Couldn't start redirecting stdout and stderr to logcat.");
        }
    }

    //Start node, with argc and argv.
    jint result = jint(callintoNode(argument_count,argv));

    // Cleanup memory to prevent leaks
    for (int i = 0; i < argument_count; i++) {
        env->ReleaseStringUTFChars((jstring)object_refs[i], string_refs[i]);
        env->DeleteLocalRef(object_refs[i]);
    }
    delete[] object_refs;
    delete[] string_refs;
    free(args_buffer);

    // Cleanup cached JNI references
    if (cached_class) {
        env->DeleteGlobalRef(cached_class);
        cached_class = nullptr;
        cached_method = nullptr;
    }
    cached_jvm = nullptr;

    return result;
}
