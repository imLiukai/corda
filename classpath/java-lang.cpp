#include "math.h"
#include "stdlib.h"
#include "sys/time.h"
#include "time.h"
#include "time.h"
#include "string.h"
#include "stdio.h"
#include "stdint.h"
#include "jni.h"
#include "jni-util.h"

#ifdef WIN32
#  include "windows.h"
#endif

#ifdef __APPLE__
#  define SO_SUFFIX ".jnilib"
#else
#  define SO_SUFFIX ".so"
#endif

#undef JNIEXPORT
#define JNIEXPORT __attribute__ ((visibility("default")))

extern "C" JNIEXPORT jstring JNICALL
Java_java_lang_System_getProperty(JNIEnv* e, jclass, jint code)
{
  enum {
    LineSeparator = 100,
    FileSeparator = 101,
    OsName = 102,
    JavaIoTmpdir = 103,
    UserHome = 104
  };

  switch (code) {
  case LineSeparator:
    return e->NewStringUTF("\n");
    
  case FileSeparator:
    return e->NewStringUTF("/");
    
  case OsName:
    return e->NewStringUTF("posix");

  case JavaIoTmpdir:
    return e->NewStringUTF("/tmp");

  case UserHome: {
#ifdef WIN32
    LPWSTR home = _wgetenv(L"USERPROFILE");
    return e->NewString(reinterpret_cast<jchar*>(home), lstrlenW(home));
#else
    return e->NewStringUTF(getenv("HOME"));
#endif
  }

  default:
    throwNew(e, "java/lang/RuntimeException", 0);
    return 0;
  }
}

extern "C" JNIEXPORT jlong JNICALL
Java_java_lang_System_currentTimeMillis(JNIEnv*, jclass)
{
#ifdef WIN32
  static LARGE_INTEGER frequency;
  static LARGE_INTEGER time;
  static bool init = true;

  if (init) {
    QueryPerformanceFrequency(&frequency);

    if (frequency.QuadPart == 0) {
      return 0;      
    }

    init = false;
  }

  QueryPerformanceCounter(&time);
  return static_cast<int64_t>
    (((static_cast<double>(time.QuadPart)) * 1000.0) /
     (static_cast<double>(frequency.QuadPart)));
#else
  timeval tv = { 0, 0 };
  gettimeofday(&tv, 0);
  return (static_cast<jlong>(tv.tv_sec) * 1000) +
    (static_cast<jlong>(tv.tv_usec) / 1000);
#endif
}

extern "C" JNIEXPORT jstring JNICALL
Java_java_lang_System_doMapLibraryName(JNIEnv* e, jclass, jstring name)
{
  jstring r = 0;
  const char* chars = e->GetStringUTFChars(name, 0);
  if (chars) {
    unsigned nameLength = strlen(chars);
    unsigned size = nameLength + 3 + sizeof(SO_SUFFIX);
    char buffer[size];
    snprintf(buffer, size, "lib%s" SO_SUFFIX, chars);
    r = e->NewStringUTF(buffer);

    e->ReleaseStringUTFChars(name, chars);
  }
  return r;
}

extern "C" JNIEXPORT jdouble JNICALL
Java_java_lang_Math_sin(JNIEnv*, jclass, jdouble val)
{
  return sin(val);
}

extern "C" JNIEXPORT jdouble JNICALL
Java_java_lang_Math_sqrt(JNIEnv*, jclass, jdouble val)
{
  return sqrt(val);
}

extern "C" JNIEXPORT jdouble JNICALL
Java_java_lang_Math_pow(JNIEnv*, jclass, jdouble val, jdouble exp)
{
  return pow(val, exp);
}

extern "C" JNIEXPORT jdouble JNICALL
Java_java_lang_Math_floor(JNIEnv*, jclass, jdouble val)
{
  return floor(val);
}

extern "C" JNIEXPORT jdouble JNICALL
Java_java_lang_Math_ceil(JNIEnv*, jclass, jdouble val)
{
  return ceil(val);
}

extern "C" JNIEXPORT jint JNICALL
Java_java_lang_Double_fillBufferWithDouble(JNIEnv* e, jclass, jdouble val,
					   jbyteArray buffer, jint bufferSize) {
  jboolean isCopy;
  jbyte* buf = e->GetByteArrayElements(buffer, &isCopy);
  jint count = snprintf(reinterpret_cast<char*>(buf), bufferSize, "%g", val);
  e->ReleaseByteArrayElements(buffer, buf, 0);
  return count;
}
