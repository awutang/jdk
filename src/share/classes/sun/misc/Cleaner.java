/*
 * Copyright (c) 2003, 2013, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package sun.misc;

import java.lang.ref.*;
import java.security.AccessController;
import java.security.PrivilegedAction;


/**
 * General-purpose phantom-reference-based cleaners.
 *
 * <p> Cleaners are a lightweight and more robust alternative to finalization.
 * They are lightweight because they are not created by the VM and thus do not
 * require a JNI upcall to be created, and because their cleanup code is
 * invoked directly by the reference-handler thread rather than by the
 * finalizer thread.  They are more robust because they use phantom references,
 * the weakest type of reference object, thereby avoiding the nasty ordering
 * problems inherent to finalization.
 *
 * <p> A cleaner tracks a referent object and encapsulates a thunk of arbitrary
 * cleanup code.  Some time after the GC detects that a cleaner's referent has
 * become phantom-reachable, the reference-handler thread will run the cleaner.
 * Cleaners may also be invoked directly; they are thread safe and ensure that
 * they run their thunks at most once.
 *
 * <p> Cleaners are not a replacement for finalization.  They should be used
 * only when the cleanup code is extremely simple and straightforward.
 * Nontrivial cleaners are inadvisable since they risk blocking the
 * reference-handler thread and delaying further cleanup and finalization.
 *
 *
 * @author Mark Reinhold
 */

public class Cleaner
    extends PhantomReference<Object>
{

    // Dummy reference queue, needed because the PhantomReference constructor
    // insists that we pass a queue.  Nothing will ever be placed on this queue
    // since the reference handler invokes cleaners explicitly.

//    虚队列，命名很到位。之前说GC把Reference加入pending-Reference链中后，ReferenceHandler线程在处理时
//     * 是不会将对应的Reference加入列队的，而是调用Cleaner.clean方法。但如果Reference不注册ReferenceQueue，GC处理时
//     * 又无法把他加入到pending-Reference链中，所以Cleaner里面有了一个dummyQueue成员变量。
    //
    private static final ReferenceQueue<Object> dummyQueue = new ReferenceQueue<>();

    // Doubly-linked list of live cleaners, which prevents the cleaners
    // themselves from being GC'd before their referents
    // 双向链表，保存cleaner的，referents先回收再cleaner被回收，保证在referent被回收之前
    // 这些Cleaner都是存活的。
    // 为啥要需要first去维持这个回收顺序呢?
    //  1.维持回收顺序是必要的，因为clean()本来就是在referent引用的对象没有强引用了，gc时发现需要回收时才触发的.所以是referent引用对象先回收（因为现行技术需要）。
    //  2.通过first可以维持这个顺序，因为如果没有first,DirectByteBuffer.cleaner被用户误操作比如通过反射设置为null,那cleaner对象就没有强引用了，
    //  会在gc时就回收了，那堆外内存就不能被回收了；如果有first用户可以精准控制cleaner对象回收的时机（现在是放在需要回收堆外内存之前）
    //  3.clean()中回收堆外内存有用到DirectByteBuffer对象的数据么？--用的是创建Deallocator对象时传入的参数，与DirectByteBuffer对象无关了，
    //  所以DirectByteBuffer对象可以先回收
    static private Cleaner first = null;

    // 这里的next与Reference.next不一样，此处的next是cleaner双向链表指针，由java应用维护；而Reference.next是由jvm维护的
    private Cleaner
        next = null,
        prev = null;

    private static synchronized Cleaner add(Cleaner cl) {
        if (first != null) {
            cl.next = first;
            first.prev = cl;
        }
        first = cl;
        return cl;
    }

    /**
     * 将cleaner从双向链表中删除
     * @param cl
     * @return
     */
    private static synchronized boolean remove(Cleaner cl) {

        // If already removed, do nothing
        if (cl.next == cl)
            return false;

        // Update list
        if (first == cl) {
            if (cl.next != null)
                first = cl.next;
            else
                first = cl.prev;
        }
        if (cl.next != null)
            cl.next.prev = cl.prev;
        if (cl.prev != null)
            cl.prev.next = cl.next;

        // Indicate removal by pointing the cleaner to itself
        cl.next = cl;
        cl.prev = cl;
        return true;

    }

    private final Runnable thunk;

    /**
     * 构造方法私有，保证所有cleaner对象都需要通过create()创建
     * @param referent
     * @param thunk
     */
    private Cleaner(Object referent, Runnable thunk) {
        super(referent, dummyQueue);
        this.thunk = thunk;
    }

    /**
     * Creates a new cleaner.
     *
     * @param  ob the referent object to be cleaned
     * @param  thunk
     *         The cleanup code to be run when the cleaner is invoked.  The
     *         cleanup code is run directly from the reference-handler thread,
     *         so it should be as simple and straightforward as possible.
     *
     * @return  The new cleaner
     */
    public static Cleaner create(Object ob, Runnable thunk) {
        if (thunk == null)
            return null;
        return add(new Cleaner(ob, thunk));
    }

    /**
     * Runs this cleaner, if it has not been run before.
     *
     * clean()中会去释放直接内存，那么何时触发的clean()呢？
     * --从Reference.tryHandlePending(),前提是referent指向的DirectByteBuffer对象无任何强引用且jvm执行gc
     */
    public void clean() {
        // 从双向链表中删除本cleaner对象,这样 Cleaner 对象就可以被gc回收掉了
        if (!remove(this))
            return;
        try {
            // 回收堆外内存
            thunk.run();
        } catch (final Throwable x) {
            AccessController.doPrivileged(new PrivilegedAction<Void>() {
                    public Void run() {
                        if (System.err != null)
                            new Error("Cleaner terminated abnormally", x)
                                .printStackTrace();
                        System.exit(1);
                        return null;
                    }});
        }
    }

}
