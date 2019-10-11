package com.exxbrain.android.biometric;

import android.app.Fragment;
import android.arch.lifecycle.Lifecycle;
import android.arch.lifecycle.LifecycleObserver;
import android.arch.lifecycle.OnLifecycleEvent;

import java.lang.reflect.Method;

class LifecycleEvents {
    LifecycleObserver observer;
    LifecycleEvents(LifecycleObserver observer) {
        this.observer = observer;
    }

    void raise(Lifecycle.Event event, Fragment fragment) {
        Method[] methods = observer.getClass().getDeclaredMethods();
        for(Method mt : methods) {
            if (mt.isAnnotationPresent(OnLifecycleEvent.class)) {
                OnLifecycleEvent annotation = mt.getAnnotation(OnLifecycleEvent.class);
                if (annotation.value() == event) {
                    try {
                        mt.invoke(observer, fragment);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
            }
        }
    }
}
