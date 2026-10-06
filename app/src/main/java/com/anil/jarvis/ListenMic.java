package com.anil.jarvis;

/** A listen on Jarvis's own mic that can be stopped (Ears, or TeluguEars without internet). */
interface ListenMic {
    /** "Done" (he tapped): stop listening now and write out what he said so far. */
    void finishNow();

    /** Stop; nothing more is reported. */
    void cancel();
}
