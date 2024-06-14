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
#include <stdlib.h>
#include <string.h>

#include "jvmti.h"

#define CHECK_JVMTI(x) CHECK_EQ((x), JVMTI_ERROR_NONE)

#define JVMTI_THROW_IF_ERROR(x, if_fail) do { int result = (x); if (result != JVMTI_ERROR_NONE) { throwJvmtiError(jni, result, #x); if_fail; } } while (false)

// Note: Art jvmti capabilities are documented here:
// https://android.googlesource.com/platform/art/+/refs/heads/main/openjdkjvmti/art_jvmti.h#256

//using namespace std;
//
typedef struct {
 jvmtiEnv *jvmti;
 jclass stoicJvmtiVmClass;

 // callbacks
 jmethodID nativeCallbackOnBreakpoint;
 jmethodID nativeCallbackOnMethodEntry;
 jmethodID nativeCallbackOnMethodExit;

 // Method stuff
 jclass stoicJvmtiMethodClass;
 jmethodID stoicJvmtiMethodCtor;
 jfieldID stoicJvmtiMethodMethodId;
 jfieldID stoicJvmtiMethodPrivateClazz;
 jfieldID stoicJvmtiMethodPrivateName;
 jfieldID stoicJvmtiMethodPrivateSignature;
 jfieldID stoicJvmtiMethodPrivateGeneric;
 jfieldID stoicJvmtiMethodPrivateStartLocation;
 jfieldID stoicJvmtiMethodPrivateEndLocation;
 jfieldID stoicJvmtiMethodPrivateArgsSize;
 jfieldID stoicJvmtiMethodPrivateMaxLocals;
} GlobalAgentData;
 
static GlobalAgentData *gdata;

// We don't want to process a callback for our callback to the VM
// TODO: We could probably be more efficient with SetEventNotificationMode
// TODO: We might want to allow the callback to ask for callbacks to be
// temporarily re-enabled
thread_local bool callbacksAllowed = true;

static void
throwJvmtiError(JNIEnv* jni, int result, const char* desc) {
  ScopedLocalRef<jclass> jvmtiExceptionClass(jni, jni->FindClass("com/square/stoic/jvmti/JvmtiException"));
  CHECK(jvmtiExceptionClass.get() != NULL);
  jmethodID ctor = jni->GetMethodID(jvmtiExceptionClass.get(), "<init>", "(ILjava/lang/String;)V");
  CHECK(ctor != NULL);
  ScopedLocalRef<jstring> jdesc(jni, jni->NewStringUTF(desc));
  ScopedLocalRef<jthrowable> exc(jni, (jthrowable) jni->NewObject(jvmtiExceptionClass.get(), ctor, result, jdesc.get()));
  CHECK(exc.get() != NULL);
  jni->Throw(exc.get());
}
 
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
Jvmti_VirtualMachine_nativeGetInstances(JNIEnv *jni, jobject vmClass, jclass klass, jboolean includeSubclasses) {
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

JNIEXPORT jlong JNICALL
Jvmti_VirtualMachine_nativeGetMethodId(JNIEnv *jni, jobject vmClass, jclass clazz, jstring methodName, jstring methodSignature) {
  const char* methodNameChars = jni->GetStringUTFChars(methodName, NULL);
  const char* methodSignatureChars = jni->GetStringUTFChars(methodSignature, NULL);
  jmethodID methodId = jni->GetMethodID(clazz, methodNameChars, methodSignatureChars);
  jni->ReleaseStringUTFChars(methodName, methodNameChars);
  jni->ReleaseStringUTFChars(methodSignature, methodSignatureChars);

  return (jlong) methodId;
}

JNIEXPORT void JNICALL
Jvmti_VirtualMachine_nativeSetBreakpoint(JNIEnv *jni, jobject vmClass, jlong methodId, jlong location) {
  jvmtiEnv* jvmti = gdata->jvmti;
  jmethodID castMethodId = reinterpret_cast<jmethodID>(methodId);
  CHECK_JVMTI(jvmti->SetBreakpoint(castMethodId, location));
}

JNIEXPORT void JNICALL
Jvmti_VirtualMachine_nativeClearBreakpoint(JNIEnv *jni, jobject vmClass, jlong methodId, jlong location) {
  jvmtiEnv* jvmti = gdata->jvmti;
  CHECK_JVMTI(jvmti->ClearBreakpoint((jmethodID) methodId, (jlocation) location));
}

JNIEXPORT void JNICALL
Jvmti_VirtualMachine_nativeGetMethodCoreMetadata(JNIEnv *jni, jobject vmClass, jobject method) {
  jvmtiEnv* jvmti = gdata->jvmti;

  jlong longMethodId = jni->GetLongField(method, gdata->stoicJvmtiMethodMethodId);
  jmethodID castMethodId = reinterpret_cast<jmethodID>(longMethodId);

  char* name = NULL;
  char* signature = NULL;
  char* generic = NULL;
  CHECK_JVMTI(jvmti->GetMethodName(castMethodId, &name, &signature, &generic));
  ScopedLocalRef<jstring> jname(jni, jni->NewStringUTF(name));
  ScopedLocalRef<jstring> jsignature(jni, jni->NewStringUTF(signature));
  ScopedLocalRef<jstring> jgeneric(jni, jni->NewStringUTF(generic));

  jlocation startLocation = -1;
  jlocation endLocation = -1;

  jvmtiError error = JVMTI_ERROR_NONE;
  error = jvmti->GetMethodLocation(castMethodId, &startLocation, &endLocation);
  if (error != JVMTI_ERROR_NATIVE_METHOD) { CHECK_JVMTI(error); }

  jint argsSize = -1;
  error = jvmti->GetArgumentsSize(castMethodId, &argsSize);
  if (error != JVMTI_ERROR_NATIVE_METHOD) { CHECK_JVMTI(error); }

  jint maxLocals = -1;
  error = jvmti->GetMaxLocals(castMethodId, &maxLocals);
  if (error != JVMTI_ERROR_NATIVE_METHOD) { CHECK_JVMTI(error); }

  jclass declaringClass = nullptr;
  CHECK_JVMTI(jvmti->GetMethodDeclaringClass(castMethodId, &declaringClass));
  jni->SetObjectField(method, gdata->stoicJvmtiMethodPrivateClazz, declaringClass);
  jni->DeleteLocalRef(declaringClass);
  declaringClass = nullptr;

  jni->SetObjectField(method, gdata->stoicJvmtiMethodPrivateName, jname.get());
  jni->SetObjectField(method, gdata->stoicJvmtiMethodPrivateSignature, jsignature.get());
  jni->SetObjectField(method, gdata->stoicJvmtiMethodPrivateGeneric, jgeneric.get());
  jni->SetLongField(method, gdata->stoicJvmtiMethodPrivateStartLocation, startLocation);
  jni->SetLongField(method, gdata->stoicJvmtiMethodPrivateEndLocation, endLocation);
  jni->SetIntField(method, gdata->stoicJvmtiMethodPrivateArgsSize, argsSize);
  jni->SetIntField(method, gdata->stoicJvmtiMethodPrivateMaxLocals, maxLocals);

  jvmti->Deallocate((unsigned char*) name);
  jvmti->Deallocate((unsigned char*) signature);
  jvmti->Deallocate((unsigned char*) generic);
}

JNIEXPORT jobject JNICALL
Jvmti_VirtualMachine_nativeGetLocalVariables(JNIEnv *jni, jobject vmClass, jlong methodId) {
  jvmtiEnv* jvmti = gdata->jvmti;
  jmethodID castMethodId = reinterpret_cast<jmethodID>(methodId);
  jint entryCount = -1;
  jvmtiLocalVariableEntry* table = NULL;

  // This can fail if local variable information isn't available
  JVMTI_THROW_IF_ERROR(jvmti->GetLocalVariableTable(castMethodId, &entryCount, &table), return NULL);

  ScopedLocalRef<jclass> LocalVariable(jni, jni->FindClass("com/square/stoic/jvmti/LocalVariable"));
  CHECK(LocalVariable.get() != NULL);
  jmethodID ctor = jni->GetMethodID(LocalVariable.get(), "<init>", "(JILjava/lang/String;Ljava/lang/String;Ljava/lang/String;I)V");
  CHECK(ctor != NULL);
  ScopedLocalRef<jobjectArray> jTable(jni, jni->NewObjectArray(entryCount, LocalVariable.get(), NULL));
  CHECK(jTable.get() != NULL);

  for (int i = 0; i < entryCount; i++) {
    jlong startLocation = table[i].start_location;
    jint length = table[i].length;
    ScopedLocalRef<jstring> name(jni, jni->NewStringUTF(table[i].name));
    ScopedLocalRef<jstring> signature(jni, jni->NewStringUTF(table[i].signature));
    ScopedLocalRef<jstring> genericSignature(jni, jni->NewStringUTF(table[i].generic_signature));
    jint slot = table[i].slot;
    ScopedLocalRef<jobject> localVar(jni, jni->NewObject(LocalVariable.get(), ctor, startLocation, length, name.get(), signature.get(), genericSignature.get(), slot));
    CHECK(localVar.get() != NULL);
    jni->SetObjectArrayElement(jTable.get(), i, localVar.get());
  }

  // Deallocate
  for (int i = 0; i < entryCount; i++) {
    CHECK_JVMTI(jvmti->Deallocate((unsigned char*) table[i].name));
    CHECK_JVMTI(jvmti->Deallocate((unsigned char*) table[i].signature));
    CHECK_JVMTI(jvmti->Deallocate((unsigned char*) table[i].generic_signature));
    table[i].name = table[i].signature = table[i].generic_signature = NULL;
  }
  CHECK_JVMTI(jvmti->Deallocate((unsigned char*) table));
  table = NULL;

  return jTable.release();
}

JNIEXPORT jobject JNICALL
Jvmti_VirtualMachine_nativeGetLocalObject(JNIEnv *jni, jobject vmClass, jthread thread, jint height, jint slot) {
  jvmtiEnv* jvmti = gdata->jvmti;
  jint frameCount = -1;
  CHECK_JVMTI(jvmti->GetFrameCount(thread, &frameCount));
  jobject result = NULL;
  CHECK_JVMTI(jvmti->GetLocalObject(thread, frameCount - height, slot, &result));
  return result;
}

JNIEXPORT jint JNICALL
Jvmti_VirtualMachine_nativeGetLocalInt(JNIEnv *jni, jobject vmClass, jthread thread, jint height, jint slot) {
  jvmtiEnv* jvmti = gdata->jvmti;
  jint frameCount = -1;
  CHECK_JVMTI(jvmti->GetFrameCount(thread, &frameCount));
  jint result = 0;
  CHECK_JVMTI(jvmti->GetLocalInt(thread, frameCount - height, slot, &result));
  return result;
}

JNIEXPORT jlong JNICALL
Jvmti_VirtualMachine_nativeGetLocalLong(JNIEnv *jni, jobject vmClass, jthread thread, jint height, jint slot) {
  jvmtiEnv* jvmti = gdata->jvmti;
  jint frameCount = -1;
  CHECK_JVMTI(jvmti->GetFrameCount(thread, &frameCount));
  jlong result = 0;
  CHECK_JVMTI(jvmti->GetLocalLong(thread, frameCount - height, slot, &result));
  return result;
}

JNIEXPORT jfloat JNICALL
Jvmti_VirtualMachine_nativeGetLocalFloat(JNIEnv *jni, jobject vmClass, jthread thread, jint height, jint slot) {
  jvmtiEnv* jvmti = gdata->jvmti;
  jint frameCount = -1;
  CHECK_JVMTI(jvmti->GetFrameCount(thread, &frameCount));
  jfloat result = 0.0f;
  CHECK_JVMTI(jvmti->GetLocalFloat(thread, frameCount - height, slot, &result));
  return result;
}

JNIEXPORT jdouble JNICALL
Jvmti_VirtualMachine_nativeGetLocalDouble(JNIEnv *jni, jobject vmClass, jthread thread, jint height, jint slot) {
  jvmtiEnv* jvmti = gdata->jvmti;
  jint frameCount = -1;
  CHECK_JVMTI(jvmti->GetFrameCount(thread, &frameCount));
  jdouble result = 0.0;
  CHECK_JVMTI(jvmti->GetLocalDouble(thread, frameCount - height, slot, &result));
  return result;
}

JNIEXPORT jobject JNICALL
Jvmti_VirtualMachine_nativeGetClassMethods(JNIEnv *jni, jobject vmClass, jclass clazz) {
  jvmtiEnv* jvmti = gdata->jvmti;
  jint methodCount = -1;
  jmethodID* methods = NULL;
  CHECK_JVMTI(jvmti->GetClassMethods(clazz, &methodCount, &methods));

  ScopedLocalRef<jobjectArray> jmethods(jni, jni->NewObjectArray(methodCount, gdata->stoicJvmtiMethodClass, NULL));
  CHECK(jmethods.get() != NULL);
  for (int i = 0; i < methodCount; i++) {
    jlong longMethodId = reinterpret_cast<jlong>(methods[i]);
    ScopedLocalRef<jobject> method(jni, jni->NewObject(
          gdata->stoicJvmtiMethodClass,
          gdata->stoicJvmtiMethodCtor,
          longMethodId));

    jni->SetObjectArrayElement(jmethods.get(), i, method.get());
  }

  jvmti->Deallocate((unsigned char*) methods);

  return jmethods.release();
}

JNIEXPORT jobject JNICALL
Jvmti_VirtualMachine_nativeToReflectedMethod(JNIEnv *jni, jobject vmClass, jclass clazz, jlong methodId, jboolean isStatic) {
  jmethodID castMethodId = reinterpret_cast<jmethodID>(methodId);
  return jni->ToReflectedMethod(clazz, castMethodId, isStatic);
}

JNIEXPORT jstring JNICALL
Jvmti_VirtualMachine_nativeGetClassSignature(JNIEnv *jni, jobject vmClass, jclass clazz) {
  char* signature = NULL;
  char* generic = NULL;
  CHECK_JVMTI(gdata->jvmti->GetClassSignature(clazz, &signature, &generic));
  ScopedLocalRef<jstring> jsignature(jni, jni->NewStringUTF(signature));
  //ScopedLocalRef<jstring> jgeneric(jni, jni->NewStringUTF(generic));
  gdata->jvmti->Deallocate((unsigned char*) signature);
  gdata->jvmti->Deallocate((unsigned char*) generic);

  return jsignature.release();
}

JNIEXPORT void JNICALL
Jvmti_VirtualMachine_nativeMethodEntryCallbacks(JNIEnv *jni, jobject vmClass, jthread thread, jboolean isEnabled) {
  CHECK_JVMTI(gdata->jvmti->SetEventNotificationMode(isEnabled ? JVMTI_ENABLE : JVMTI_DISABLE, JVMTI_EVENT_METHOD_ENTRY, thread));
}

JNIEXPORT void JNICALL
Jvmti_VirtualMachine_nativeMethodExitCallbacks(JNIEnv *jni, jobject vmClass, jthread thread, jboolean isEnabled) {
  CHECK_JVMTI(gdata->jvmti->SetEventNotificationMode(isEnabled ? JVMTI_ENABLE : JVMTI_DISABLE, JVMTI_EVENT_METHOD_EXIT, thread));
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

static void JNICALL
CbBreakpoint(jvmtiEnv* jvmti, JNIEnv* jni, jthread thread, jmethodID methodId, jlocation location) {
  if (!callbacksAllowed) {
    return;
  }

  jint count = -1;
  CHECK_JVMTI(jvmti->GetFrameCount(thread, &count));

  jlong methodIdAsLong = reinterpret_cast<jlong>(methodId);

  callbacksAllowed = false;
  jni->CallStaticVoidMethod(
      gdata->stoicJvmtiVmClass,
      gdata->nativeCallbackOnBreakpoint,
      methodIdAsLong,
      location,
      count);
  callbacksAllowed = true;
}

static void JNICALL
CbMethodEntry(
    jvmtiEnv* jvmti,
    JNIEnv* jni,
    jthread thread,
    jmethodID methodId) {
  if (!callbacksAllowed) {
    return;
  }

  jint count = -1;
  CHECK_JVMTI(jvmti->GetFrameCount(thread, &count));

  jmethodID frameMethodId = nullptr;
  jlocation location = -1;
  CHECK_JVMTI(jvmti->GetFrameLocation(thread, 0, &frameMethodId, &location));
  CHECK_EQ(frameMethodId, methodId);

  jlong methodIdAsLong = reinterpret_cast<jlong>(methodId);

  callbacksAllowed = false;
  jni->CallStaticVoidMethod(
      gdata->stoicJvmtiVmClass,
      gdata->nativeCallbackOnMethodEntry,
      methodIdAsLong,
      location,
      count);
  callbacksAllowed = true;
}

static void JNICALL
CbMethodExit(
    jvmtiEnv* jvmti,
    JNIEnv* jni,
    jthread thread,
    jmethodID methodId,
    jboolean was_popped_by_exception,
    jvalue return_value) {
  if (!callbacksAllowed) {
    return;
  }

  jint count = -1;
  CHECK_JVMTI(jvmti->GetFrameCount(thread, &count));

  jmethodID frameMethodId = nullptr;
  jlocation location = -1;
  CHECK_JVMTI(jvmti->GetFrameLocation(thread, 0, &frameMethodId, &location));
  CHECK_EQ(frameMethodId, methodId);
  jlong methodIdAsLong = reinterpret_cast<jlong>(methodId);

  // TODO: surface return_value (it's complicated because its a union)
  callbacksAllowed = false;
  jni->CallStaticVoidMethod(
      gdata->stoicJvmtiVmClass,
      gdata->nativeCallbackOnMethodExit,
      methodIdAsLong,
      location,
      count,
      was_popped_by_exception);
  callbacksAllowed = true;
}

static void AgentMain(jvmtiEnv* jvmti, JNIEnv* jni, [[maybe_unused]] void* arg) {
  LOG(DEBUG) << "Running AgentMain";
  // store jvmti in a global data
  gdata = (GlobalAgentData*) calloc(1, sizeof(GlobalAgentData));

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

  {
    ScopedLocalRef<jstring> stoicJvmtiVmClassName(jni, jni->NewStringUTF("com.square.stoic.jvmti.VirtualMachine"));
    CHECK(stoicJvmtiVmClassName.get() != nullptr);

    ScopedLocalRef<jclass> klass_stoicJvmtiVm(jni, (jclass) jni->CallObjectMethod(dexClassLoader.get(), method_ClassLoader_loadClass, stoicJvmtiVmClassName.get()));
    CHECK(klass_stoicJvmtiVm.get() != nullptr);
    LOG(DEBUG) << "Found stoic.jvmti.VirtualMachine class";
    gdata->stoicJvmtiVmClass = (jclass) jni->NewGlobalRef(klass_stoicJvmtiVm.get());
  }

  {
    ScopedLocalRef<jstring> stoicJvmtiMethodName(jni, jni->NewStringUTF("com.square.stoic.jvmti.Method"));
    CHECK(stoicJvmtiMethodName.get() != nullptr);

    ScopedLocalRef<jclass> klass_stoicJvmtiMethod(jni, (jclass) jni->CallObjectMethod(dexClassLoader.get(), method_ClassLoader_loadClass, stoicJvmtiMethodName.get()));
    CHECK(klass_stoicJvmtiMethod.get() != nullptr);
    gdata->stoicJvmtiMethodClass = (jclass) jni->NewGlobalRef(klass_stoicJvmtiMethod.get());

    gdata->stoicJvmtiMethodCtor = jni->GetMethodID(gdata->stoicJvmtiMethodClass, "<init>", "(J)V");
    CHECK(gdata->stoicJvmtiMethodCtor != NULL);

    gdata->stoicJvmtiMethodMethodId = jni->GetFieldID(gdata->stoicJvmtiMethodClass, "methodId", "J");
    CHECK(gdata->stoicJvmtiMethodMethodId != NULL);

    gdata->stoicJvmtiMethodPrivateClazz = jni->GetFieldID(gdata->stoicJvmtiMethodClass, "privateClazz", "Ljava/lang/Class;");
    CHECK(gdata->stoicJvmtiMethodPrivateClazz != NULL);

    gdata->stoicJvmtiMethodPrivateName = jni->GetFieldID(gdata->stoicJvmtiMethodClass, "privateName", "Ljava/lang/String;");
    CHECK(gdata->stoicJvmtiMethodPrivateName != NULL);

    gdata->stoicJvmtiMethodPrivateSignature = jni->GetFieldID(gdata->stoicJvmtiMethodClass, "privateSignature", "Ljava/lang/String;");
    CHECK(gdata->stoicJvmtiMethodPrivateSignature != NULL);

    gdata->stoicJvmtiMethodPrivateGeneric = jni->GetFieldID(gdata->stoicJvmtiMethodClass, "privateGeneric", "Ljava/lang/String;");
    CHECK(gdata->stoicJvmtiMethodPrivateGeneric != NULL);

    gdata->stoicJvmtiMethodPrivateStartLocation = jni->GetFieldID(gdata->stoicJvmtiMethodClass, "privateStartLocation", "J");
    CHECK(gdata->stoicJvmtiMethodPrivateStartLocation != NULL);

    gdata->stoicJvmtiMethodPrivateEndLocation = jni->GetFieldID(gdata->stoicJvmtiMethodClass, "privateEndLocation", "J");
    CHECK(gdata->stoicJvmtiMethodPrivateEndLocation != NULL);

    gdata->stoicJvmtiMethodPrivateArgsSize = jni->GetFieldID(gdata->stoicJvmtiMethodClass, "privateArgsSize", "I");
    CHECK(gdata->stoicJvmtiMethodPrivateArgsSize != NULL);

    gdata->stoicJvmtiMethodPrivateMaxLocals = jni->GetFieldID(gdata->stoicJvmtiMethodClass, "privateMaxLocals", "I");
    CHECK(gdata->stoicJvmtiMethodPrivateMaxLocals != NULL);
  }

  gdata->nativeCallbackOnBreakpoint = jni->GetStaticMethodID(gdata->stoicJvmtiVmClass, "nativeCallbackOnBreakpoint", "(JJI)V");
  gdata->nativeCallbackOnMethodEntry = jni->GetStaticMethodID(gdata->stoicJvmtiVmClass, "nativeCallbackOnMethodEntry", "(JJI)V");
  gdata->nativeCallbackOnMethodExit = jni->GetStaticMethodID(gdata->stoicJvmtiVmClass, "nativeCallbackOnMethodExit", "(JJIZ)V");

  JNINativeMethod methods[] = {
    {"nativeGetInstances",              "(Ljava/lang/Class;Z)[Ljava/lang/Object;",                      (void *)&Jvmti_VirtualMachine_nativeGetInstances},
    {"nativeGetMethodId",               "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/String;)J",     (void *)&Jvmti_VirtualMachine_nativeGetMethodId},
    {"nativeSetBreakpoint",             "(JJ)V",                                                        (void *)&Jvmti_VirtualMachine_nativeSetBreakpoint},
    {"nativeClearBreakpoint",           "(JJ)V",                                                        (void *)&Jvmti_VirtualMachine_nativeClearBreakpoint},
    {"nativeGetMethodCoreMetadata",     "(Lcom/square/stoic/jvmti/Method;)V",                           (void *)&Jvmti_VirtualMachine_nativeGetMethodCoreMetadata},
    {"nativeGetLocalVariables",         "(J)[Lcom/square/stoic/jvmti/LocalVariable;",                   (void *)&Jvmti_VirtualMachine_nativeGetLocalVariables},
    {"nativeGetLocalObject",            "(Ljava/lang/Thread;II)Ljava/lang/Object;",                     (void *)&Jvmti_VirtualMachine_nativeGetLocalObject},
    {"nativeGetLocalInt",               "(Ljava/lang/Thread;II)I",                                      (void *)&Jvmti_VirtualMachine_nativeGetLocalInt},
    {"nativeGetLocalLong",              "(Ljava/lang/Thread;II)J",                                      (void *)&Jvmti_VirtualMachine_nativeGetLocalLong},
    {"nativeGetLocalFloat",             "(Ljava/lang/Thread;II)F",                                      (void *)&Jvmti_VirtualMachine_nativeGetLocalFloat},
    {"nativeGetLocalDouble",            "(Ljava/lang/Thread;II)D",                                      (void *)&Jvmti_VirtualMachine_nativeGetLocalDouble},
    {"nativeGetClassMethods",           "(Ljava/lang/Class;)[Lcom/square/stoic/jvmti/Method;",          (void *)&Jvmti_VirtualMachine_nativeGetClassMethods},
    {"nativeToReflectedMethod",         "(Ljava/lang/Class;JZ)Ljava/lang/Object;",                      (void *)&Jvmti_VirtualMachine_nativeToReflectedMethod},
    {"nativeGetClassSignature",         "(Ljava/lang/Class;)Ljava/lang/String;",                        (void *)&Jvmti_VirtualMachine_nativeGetClassSignature},
    {"nativeMethodEntryCallbacks",      "(Ljava/lang/Thread;Z)V",                                       (void *)&Jvmti_VirtualMachine_nativeMethodEntryCallbacks},
    {"nativeMethodExitCallbacks",       "(Ljava/lang/Thread;Z)V",                                       (void *)&Jvmti_VirtualMachine_nativeMethodExitCallbacks},
  };

  CHECK(jni->RegisterNatives(gdata->stoicJvmtiVmClass, methods, sizeof(methods) / sizeof(methods[0])) == JNI_OK);


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
    .can_tag_objects = JNI_TRUE,
    .can_access_local_variables = JNI_TRUE,
    .can_generate_breakpoint_events = JNI_TRUE,
    .can_generate_method_entry_events = JNI_TRUE,
    .can_generate_method_exit_events = JNI_TRUE,
    .can_force_early_return = JNI_TRUE,
  };
  CHECK_JVMTI(jvmti->AddCapabilities(&caps) != JVMTI_ERROR_NONE);

  jvmtiEventCallbacks cb{
    .VMInit = CbVmInit,
    .Breakpoint = CbBreakpoint,
    .MethodEntry = CbMethodEntry,
    .MethodExit = CbMethodExit,
  };
  CHECK_JVMTI(jvmti->SetEventCallbacks(&cb, sizeof(cb)));
  CHECK_JVMTI(jvmti->SetEnvironmentLocalStorage(reinterpret_cast<void*>(ai)));
  CHECK_JVMTI(jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_BREAKPOINT, nullptr /* all threads */));

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
