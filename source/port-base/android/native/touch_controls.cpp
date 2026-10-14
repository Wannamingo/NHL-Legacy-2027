#include <jni.h>
#include <cstdint>

// Android runtime already merges this state into player 1's SDL controller.
extern "C" void REX_AndroidSetTouchGamepadState(int buttons, float left_x,
    float left_y, float right_x, float right_y, float left_trigger,
    float right_trigger);

extern "C" JNIEXPORT void JNICALL
Java_org_nhllegacy_port_GameActivity_nativeTouchPad(JNIEnv*, jclass,
    jint buttons, jfloat lx, jfloat ly, jfloat rx, jfloat ry,
    jfloat lt, jfloat rt, jboolean connected) {
  REX_AndroidSetTouchGamepadState(connected == JNI_TRUE ? buttons : -1,
                                 lx, ly, rx, ry, lt, rt);
}
