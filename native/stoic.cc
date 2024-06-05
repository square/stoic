#include <cstddef>
#include <fcntl.h>
#include <fstream>
#include <memory>
#include <sstream>
#include <string>
#include <unistd.h>
#include <unordered_set>

#define LOG_TAG "stoic"

#include <android/log.h>
#include <android-base/logging.h>
#include <android-base/macros.h>
#include <nativehelper/scoped_local_ref.h>
#include <nativehelper/scoped_utf_chars.h>

#include <jni.h>
#include <jvmti.h>
#include <stdlib.h>
#include <string.h>

#define CHECK_JVMTI(x) CHECK_EQ((x), JVMTI_ERROR_NONE)

// Note: Art jvmti capabilities are documented here:
// https://android.googlesource.com/platform/art/+/refs/heads/main/openjdkjvmti/art_jvmti.h#256

//using namespace std;
//
typedef struct {
 jvmtiEnv *jvmti;
} GlobalAgentData;
 
static GlobalAgentData *gdata;
 
jvmtiIterationControl JNICALL
jvmtiHeapObjectCallback_tagUnconditionally(jlong class_tag, jlong size, jlong* tag_ptr, void* user_data) {
  *tag_ptr = 1;
  return JVMTI_ITERATION_CONTINUE;
}

jvmtiIterationControl JNICALL
jvmtiHeapObjectCallback_untagUnconditionally(jlong class_tag, jlong size, jlong* tag_ptr, void* user_data) {
  *tag_ptr = 0;
  return JVMTI_ITERATION_CONTINUE;
}

jint JNICALL
jvmtiHeapIterationCallback_tagUnconditionally(jlong class_tag, jlong size, jlong* tag_ptr, jint length, void* user_data) {
  *tag_ptr = *static_cast<jlong*>(user_data);
  return 0;
}

jint JNICALL
jvmtiHeapIterationCallback_untagUnconditionally(jlong class_tag, jlong size, jlong* tag_ptr, jint length, void* user_data) {
  *tag_ptr = 0;
  return 0;
}

JNIEXPORT jobject JNICALL
StoicJvmti_getInstances(JNIEnv *jni, jobject stoicJvmti, jclass klass, jboolean includeSubclasses) {
  jvmtiEnv* jvmti = gdata->jvmti;

  // Clear all tags in the heap
  {
    jvmtiHeapCallbacks callbacks = {
      .heap_iteration_callback = jvmtiHeapIterationCallback_untagUnconditionally,
    };

    CHECK_JVMTI(jvmti->IterateThroughHeap(
          JVMTI_HEAP_FILTER_UNTAGGED,
          nullptr,
          &callbacks,
          nullptr));
  }

  if (includeSubclasses) {
    // klassClass = java.lang.Class
    ScopedLocalRef<jclass> klassClass(jni, jni->FindClass("java/lang/Class"));
    if (!klassClass.get()) {
        jni->ExceptionDescribe();
        jni->ExceptionClear();
        return nullptr;
    }

    // Tag every Class object
    jlong tag = 1;
    CHECK_JVMTI(jvmti->IterateOverInstancesOfClass(
          klassClass.get(),
          JVMTI_HEAP_OBJECT_EITHER,
          jvmtiHeapObjectCallback_tagUnconditionally,
          &tag));

    // Iterate through every Class object, clearing the tags of classes that aren't subclasses of klass
    jobject* obj_list;
    jint obj_len;
    jlong tagOne = 1;
    CHECK_JVMTI(jvmti->GetObjectsWithTags(1, &tagOne, &obj_len, &obj_list, nullptr));
    jmethodID method_isAssignableFrom = jni->GetMethodID(klassClass.get(), "isAssignableFrom", "(Ljava/lang/Class;)Z");
    CHECK(method_isAssignableFrom != nullptr);
    for (int i = 0; i < obj_len; i++) {
      ScopedLocalRef<jobject> klassCandidate(jni, obj_list[i]);

      // Never throws (famous last words?)
      jboolean isAssignable = jni->CallBooleanMethod(klass, method_isAssignableFrom, klassCandidate.get());
      
      if (!isAssignable) {
        jlong tagZero = 0;
        CHECK_JVMTI(jvmti->SetTag(klassCandidate.get(), tagZero));
      }
    }

    // Tag every object whose class is tagged
    {
      jvmtiHeapCallbacks callbacks = {
        .heap_iteration_callback = jvmtiHeapIterationCallback_tagUnconditionally,
      };
      CHECK_JVMTI(jvmti->IterateThroughHeap(
          JVMTI_HEAP_FILTER_CLASS_UNTAGGED,
          nullptr,
          &callbacks,
          &tagOne));
    }

    // TODO: This wouldn't work for subclasses of java.lang.Class
    // java.lang.Class is final so there aren't any, but if people
    // asked for instances of java.lang.Class and subclasses, this
    // will give the wrong answer. So I need to special-case that.

    // Untag instances of java.lang.Class
    CHECK_JVMTI(jvmti->IterateOverInstancesOfClass(
          klassClass.get(),
          JVMTI_HEAP_OBJECT_TAGGED,
          jvmtiHeapObjectCallback_untagUnconditionally,
          nullptr));
  } else {
    // tag every object whose Class is exactly klass
    jlong tag = 1;
    CHECK_JVMTI(jvmti->IterateOverInstancesOfClass(
          klass,
          JVMTI_HEAP_OBJECT_EITHER,
          jvmtiHeapObjectCallback_tagUnconditionally,
          &tag));
  }
 
  // Get all of the tagged objects
  jobject* obj_list;
  jint obj_len;
  jlong tag = 1;
  CHECK_JVMTI(jvmti->GetObjectsWithTags(1, &tag, &obj_len, &obj_list, nullptr));
 
  jobjectArray klassArray = jni->NewObjectArray(obj_len, klass, NULL);
  if (klassArray == nullptr) {
    LOG(ERROR) << "NewObjectArray failed";
    return nullptr;
  }
 
  for (int i = 0; i < obj_len; i++) {
    ScopedLocalRef<jobject> obj(jni, obj_list[i]);
    jni->SetObjectArrayElement(klassArray, i, obj.get());
  }

  return klassArray;
}
 
struct AgentInfo {
  std::string options;
};

// Converts a class name to a type descriptor
// (ex. "java.lang.String" to "Ljava/lang/String;")
std::string classNameToDescriptor(const char* className) {
  std::stringstream ss;
  ss << "L";
  for (auto p = className; *p != '\0'; ++p) {
    ss << (*p == '.' ? '/' : *p);
  }
  ss << ";";
  return ss.str();
}

// Converts a descriptor (Lthis/style/of/name;) to a jni-FindClass style Fully-qualified class name
// (this/style/of/name).
std::string DescriptorToFQCN(const std::string& descriptor) {
  return descriptor.substr(1, descriptor.size() - 2);
}

static AgentInfo* GetAgentInfo(jvmtiEnv* jvmti) {
  AgentInfo* ai = nullptr;
  CHECK_EQ(jvmti->GetEnvironmentLocalStorage(reinterpret_cast<void**>(&ai)), JVMTI_ERROR_NONE);
  CHECK(ai != nullptr);
  return ai;
}

static void AgentMain(jvmtiEnv* jvmti, JNIEnv* jni, [[maybe_unused]] void* arg) {
  LOG(DEBUG) << "Running AgentMain";
  // store jvmti in a global data
  gdata = (GlobalAgentData*) malloc(sizeof(GlobalAgentData));
  gdata->jvmti = jvmti;
  LOG(DEBUG) << "jvmti stored";

  // Options contains the stoic dir
  std::string stoicDir = GetAgentInfo(jvmti)->options;
  LOG(DEBUG) << "Found stoicDir: " << stoicDir.c_str();


  // In order to setup the ClassLoader correctly, we need to chain it to the
  // Application ClassLoader. Otherwise we might end up with duplicate classes
  // (and weird class casting problems). We find the application via
  // ActivityThread internals. This could be avoided by using JVMTI to search
  // the heap to find instances of ClassLoader and then picking the right one.
  // Or searching the heap to find the instance of Application (assuming there
  // is only one!)
  //
  // TODO: Use Looper.getMainLooper().getThread().getContextClassLoader()
  // instead

  LOG(DEBUG) << "Looking for ActivityThread";
  ScopedLocalRef<jclass> klass_ActivityThread(jni, jni->FindClass("android/app/ActivityThread"));
  CHECK(klass_ActivityThread.get() != nullptr);
  LOG(DEBUG) << "Found ActivityThread";

  jfieldID field_sCurrentActivityThread = jni->GetStaticFieldID(klass_ActivityThread.get(), "sCurrentActivityThread", "Landroid/app/ActivityThread;");
  CHECK(field_sCurrentActivityThread != nullptr);
  LOG(DEBUG) << "Found sCurrentActivityThread field";

  ScopedLocalRef<jobject> currentActivityThread(jni, jni->GetStaticObjectField(klass_ActivityThread.get(), field_sCurrentActivityThread));
  CHECK(currentActivityThread.get() != nullptr);
  LOG(DEBUG) << "Found sCurrentActivityThread";

  jfieldID field_mInitialApplication = jni->GetFieldID(klass_ActivityThread.get(), "mInitialApplication", "Landroid/app/Application;");
  CHECK(field_mInitialApplication != nullptr);
  LOG(DEBUG) << "Found mInitialApplication field";

  ScopedLocalRef<jobject> application(jni, jni->GetObjectField(currentActivityThread.get(), field_mInitialApplication));
  CHECK(application.get() != nullptr);

  ScopedLocalRef<jclass> klass_Class(jni, jni->FindClass("java/lang/Class"));
  CHECK(klass_Class.get() != nullptr);
  LOG(DEBUG) << "Found Class";

  ScopedLocalRef<jclass> klass_Application(jni, (jclass) jni->CallObjectMethod(
      application.get(),
      jni->GetMethodID(klass_Class.get(), "getClass", "()Ljava/lang/Class;")));
  CHECK(klass_Application.get() != nullptr);

  ScopedLocalRef<jobject> originalClassLoader(jni, jni->CallObjectMethod(
      klass_Application.get(),
      jni->GetMethodID(klass_Class.get(), "getClassLoader", "()Ljava/lang/ClassLoader;")));
  CHECK(originalClassLoader.get() != nullptr);
  LOG(DEBUG) << "Found originalClassLoader";


  //
  // Setup args that we need for our ClassLoader
  //

  std::string stoicDexJarChars = std::string(stoicDir.c_str()) + std::string("/stoic.dex.jar");
  LOG(DEBUG) << "Found stoicDexJarChars: " << stoicDexJarChars.c_str();

  std::string dexOutputDirChars = std::string(stoicDir.c_str()) + std::string("/dexout");
  LOG(DEBUG) << "Found dexOutputDirChars: " << dexOutputDirChars.c_str();

  ScopedLocalRef<jstring> stoicDexJarString(jni, jni->NewStringUTF(stoicDexJarChars.c_str()));
  CHECK(stoicDexJarString.get() != nullptr);

  ScopedLocalRef<jstring> dexOutputDirString(jni, jni->NewStringUTF(dexOutputDirChars.c_str()));
  CHECK(dexOutputDirString.get() != nullptr);


  //
  // Construct a new ClassLoader for stoic.dex.jar 
  //

  ScopedLocalRef<jclass> klass_DexClassLoader(jni, jni->FindClass("dalvik/system/DexClassLoader"));
  CHECK(klass_DexClassLoader.get() != nullptr);
  LOG(DEBUG) << "Found DexClassLoader";

  jmethodID method_DexClassLoader_ctor = jni->GetMethodID(
      klass_DexClassLoader.get(),
      "<init>",
      "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/ClassLoader;)V");
  CHECK(method_DexClassLoader_ctor != nullptr);

  ScopedLocalRef<jobject> dexClassLoader(jni, jni->NewObject(
      klass_DexClassLoader.get(),
      method_DexClassLoader_ctor,
      stoicDexJarString.get(),
      dexOutputDirString.get(),
      nullptr,
      originalClassLoader.get()));
  CHECK(dexClassLoader.get() != nullptr);
  LOG(DEBUG) << "Constructed dexClassLoader";


  //
  // RegisterNatives (to connect Stoic APIs with their JNI implementations)
  //

  ScopedLocalRef<jclass> klass_ClassLoader(jni, jni->FindClass("java/lang/ClassLoader"));
  CHECK(klass_ClassLoader.get() != nullptr);
  LOG(DEBUG) << "Found ClassLoader";

  jmethodID method_ClassLoader_loadClass = jni->GetMethodID(klass_ClassLoader.get(), "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
  CHECK(method_ClassLoader_loadClass != nullptr);
  LOG(DEBUG) << "Found loadClass method";

  ScopedLocalRef<jstring> stoicJvmtiClassName(jni, jni->NewStringUTF("com.square.stoic.StoicJvmti"));
  CHECK(stoicJvmtiClassName.get() != nullptr);

  ScopedLocalRef<jclass> klass_stoicJvmti(jni, (jclass) jni->CallObjectMethod(dexClassLoader.get(), method_ClassLoader_loadClass, stoicJvmtiClassName.get()));
  CHECK(klass_stoicJvmti.get() != nullptr);
  LOG(DEBUG) << "Found Stoic class";

  JNINativeMethod methods[] = {
    {"getInstances", "(Ljava/lang/Class;Z)[Ljava/lang/Object;", (void *)&StoicJvmti_getInstances}
  };

  CHECK(jni->RegisterNatives(klass_stoicJvmti.get(), methods, sizeof(methods) / sizeof(methods[0])) == JNI_OK);


  //
  // Call AndroidServerKt.main to start the Android server
  //

  ScopedLocalRef<jstring> androidServerMainClassName(jni, jni->NewStringUTF(
      "com.square.stoic.android.server.AndroidPluginServerKt"));
  CHECK(androidServerMainClassName.get() != nullptr);

  ScopedLocalRef<jclass> klass_AndroidPluginServerKt(jni, (jclass) jni->CallObjectMethod(
      dexClassLoader.get(),
      method_ClassLoader_loadClass,
      androidServerMainClassName.get()));
  CHECK(klass_AndroidPluginServerKt.get() != nullptr);
  LOG(DEBUG) << "Found AndroidPluginServerKt class";

  ScopedLocalRef<jclass> klass_Method(jni, jni->FindClass("java/lang/reflect/Method"));
  CHECK(klass_Method.get() != nullptr);
  LOG(DEBUG) << "Found Method";

  jmethodID method_AndroidPluginServerKt_main = jni->GetStaticMethodID(
      klass_AndroidPluginServerKt.get(),
      "main",
      "(Ljava/lang/String;)V");
  CHECK(method_AndroidPluginServerKt_main != nullptr);
  LOG(DEBUG) << "Found AndroidServerKt.main method";

  ScopedLocalRef<jstring> stoicDirString(jni, jni->NewStringUTF(stoicDir.c_str()));
  CHECK(stoicDirString != nullptr);
  LOG(DEBUG) << "Constructed stoicDirString";

  jni->CallStaticVoidMethod(klass_AndroidPluginServerKt.get(), method_AndroidPluginServerKt_main, stoicDirString.get());


  //
  // Done
  //

  LOG(DEBUG) << "Returned from main";
}

static void CbVmInit(jvmtiEnv* jvmti, JNIEnv* env, [[maybe_unused]] jthread thr) {
  LOG(DEBUG) << "Running CbVmInit";

  // Create a Thread object.
  ScopedLocalRef<jobject> thread_name(env, env->NewStringUTF("Agent Thread"));
  if (thread_name.get() == nullptr) {
    env->ExceptionDescribe();
    env->ExceptionClear();
    return;
  }
  ScopedLocalRef<jclass> thread_klass(env, env->FindClass("java/lang/Thread"));
  if (thread_klass.get() == nullptr) {
    env->ExceptionDescribe();
    env->ExceptionClear();
    return;
  }
  ScopedLocalRef<jobject> thread(env, env->AllocObject(thread_klass.get()));
  if (thread.get() == nullptr) {
    env->ExceptionDescribe();
    env->ExceptionClear();
    return;
  }

  env->CallNonvirtualVoidMethod(
      thread.get(),
      thread_klass.get(),
      env->GetMethodID(thread_klass.get(), "<init>", "(Ljava/lang/String;)V"),
      thread_name.get());
  env->CallVoidMethod(thread.get(), env->GetMethodID(thread_klass.get(), "setPriority", "(I)V"), 1);
  env->CallVoidMethod(
      thread.get(), env->GetMethodID(thread_klass.get(), "setDaemon", "(Z)V"), JNI_TRUE);

  jvmti->RunAgentThread(thread.get(), AgentMain, nullptr, JVMTI_THREAD_MIN_PRIORITY);
}


template <bool kIsOnLoad>
static jint AgentStart(JavaVM* vm, char* options, [[maybe_unused]] void* reserved) {
  jvmtiEnv* jvmti = nullptr;

  if (vm->GetEnv(reinterpret_cast<void**>(&jvmti), JVMTI_VERSION_1_2) != JNI_OK ||
      jvmti == nullptr) {
    LOG(ERROR) << "unable to obtain JVMTI env.";
    return JNI_ERR;
  }
  std::string sopts(options);
  AgentInfo* ai = new AgentInfo;
  ai->options = sopts;

  jvmtiCapabilities caps = {
    .can_tag_objects = 1,
  };
  if (jvmti->AddCapabilities(&caps) != JVMTI_ERROR_NONE) {
    LOG(ERROR) << "Unable to get retransform_classes capability!";
    return JNI_ERR;
  }
  jvmtiEventCallbacks cb{
    .VMInit = CbVmInit,
  };
  jvmti->SetEventCallbacks(&cb, sizeof(cb));
  jvmti->SetEnvironmentLocalStorage(reinterpret_cast<void*>(ai));
  if (kIsOnLoad) {
    LOG(DEBUG) << "kIsOnLoad";
    jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_VM_INIT, nullptr);
  } else {
    LOG(DEBUG) << "!kIsOnLoad";
    JNIEnv* jni = nullptr;
    vm->GetEnv(reinterpret_cast<void**>(&jni), JNI_VERSION_1_2);
    jthread thr;
    jvmti->GetCurrentThread(&thr);
    CbVmInit(jvmti, jni, thr);
  }


  // TODO: These LOG statements don't work on older Android devices
  LOG(DEBUG) << "Succeeded!";

  return JNI_OK;
}

// Late attachment (e.g. 'am attach-agent').
extern "C" JNIEXPORT jint JNICALL Agent_OnAttach(JavaVM* vm, char* options, void* reserved) {
  __android_log_print(ANDROID_LOG_INFO, "stoic", "Agent_OnAttach\n");
  return AgentStart<false>(vm, options, reserved);
}

// Early attachment
extern "C" JNIEXPORT jint JNICALL Agent_OnLoad(JavaVM* jvm, char* options, void* reserved) {
  __android_log_print(ANDROID_LOG_INFO, "stoic", "Agent_OnLoad\n");
  return AgentStart<true>(jvm, options, reserved);
}
