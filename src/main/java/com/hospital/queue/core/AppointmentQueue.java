package com.hospital.queue.core;

import com.hospital.queue.model.Appointment;

import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread-safe wrapper around java.util.PriorityQueue (which is NOT thread-safe by itself).
 * Every access goes through one ReentrantLock, so add/poll/peek are atomic.
 * poll() removes the head inside the lock, so two doctors can never receive the same appointment.
 */
public class AppointmentQueue {

    private final PriorityQueue<Appointment> queue = new PriorityQueue<>(Appointment.PRIORITY_ORDER);
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();

    public void add(Appointment appointment) {
        lock.lock();
        try {
            queue.add(appointment);
            notEmpty.signal();
        } finally {
            lock.unlock();
        }
    }

    /** Removes and returns the highest-priority appointment, or null if the queue is empty. */
    public Appointment poll() {
        lock.lock();
        try {
            return queue.poll();
        } finally {
            lock.unlock();
        }
    }

    /** Like poll() but waits up to the timeout for an appointment to arrive. */
    public Appointment poll(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            while (queue.isEmpty()) {
                if (nanos <= 0) {
                    return null;
                }
                nanos = notEmpty.awaitNanos(nanos);
            }
            return queue.poll();
        } finally {
            lock.unlock();
        }
    }

    /** Highest-priority pending appointment without removing it, or null. */
    public Appointment peek() {
        lock.lock();
        try {
            return queue.peek();
        } finally {
            lock.unlock();
        }
    }

    public int size() {
        lock.lock();
        try {
            return queue.size();
        } finally {
            lock.unlock();
        }
    }

    /** Copy of the queue contents in true priority order (PriorityQueue's own iterator is not ordered). */
    public List<Appointment> snapshot() {
        lock.lock();
        try {
            List<Appointment> copy = new ArrayList<>(queue);
            copy.sort(Appointment.PRIORITY_ORDER);
            return copy;
        } finally {
            lock.unlock();
        }
    }

    public void clear() {
        lock.lock();
        try {
            queue.clear();
        } finally {
            lock.unlock();
        }
    }
}
