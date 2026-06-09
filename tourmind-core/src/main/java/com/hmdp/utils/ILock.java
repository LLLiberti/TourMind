package com.hmdp.utils;

/**
 * @author lbq
 */
public interface ILock {

    boolean tryLock(long timeoutSec);

    void unlock();
}
