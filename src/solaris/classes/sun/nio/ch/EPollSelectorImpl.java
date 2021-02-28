/*
 * Copyright (c) 2005, 2013, Oracle and/or its affiliates. All rights reserved.
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

package sun.nio.ch;

import java.io.IOException;
import java.nio.channels.*;
import java.nio.channels.spi.*;
import java.util.*;
import sun.misc.*;

/**
 * An implementation of Selector for Linux 2.6+ kernels that uses
 * the epoll event notification facility.
 */
class EPollSelectorImpl
    extends SelectorImpl
{

    // File descriptors used for interrupt
    protected int fd0;
    protected int fd1;

    // The poll object
    EPollArrayWrapper pollWrapper;

    // 一个selector所有在其上注册的jdknio.channel.fd:selectionKey
    // Maps from file descriptors to keys
    private Map<Integer,SelectionKeyImpl> fdToKey;

    // True if this Selector has been closed
    private volatile boolean closed = false;

    // Lock for interrupt triggering and clearing
    private final Object interruptLock = new Object();
    private boolean interruptTriggered = false;

    /**
     * Package private constructor called by factory method in
     * the abstract superclass Selector.
     */
    EPollSelectorImpl(SelectorProvider sp) throws IOException {
        super(sp);
        long pipeFds = IOUtil.makePipe(false);
        fd0 = (int) (pipeFds >>> 32);
        fd1 = (int) pipeFds;
        // EPollArrayWrapper的构建，EpollArrayWapper将Linux的epoll相关系统调用封装成了native方法供EpollSelectorImpl使用
        pollWrapper = new EPollArrayWrapper();
        // 通过EPollArrayWrapper向epoll注册中断事件
        pollWrapper.initInterrupt(fd0, fd1);
        // 构建文件描述符-SelectionKeyImpl映射表，所有注册到selector的channel对应的SelectionKey和与之对应的文件描述符都会放入到该映射表中。
        fdToKey = new HashMap<>();
    }

    /**
     * ① 先处理注销的selectionKey队列
     * ② 进行底层的epoll_wait操作
     * ③ 再次对注销的selectionKey队列进行处理
     * ④ 更新被选择的selectionKey
     * @param timeout
     * @return
     * @throws IOException
     */
    protected int doSelect(long timeout) throws IOException {
        if (closed)
            throw new ClosedSelectorException();
        // 1. 先处理注销的selectionKey队列
        processDeregisterQueue();
        try {
            begin();
            // 2. 进行底层的epoll_wait操作
            pollWrapper.poll(timeout);
        } finally {
            end();
        }
        // 3.再次对注销的selectionKey队列进行处理 why?
        processDeregisterQueue();
        // 4. 更新被选择的selectionKey
        int numKeysUpdated = updateSelectedKeys();
        if (pollWrapper.interrupted()) {
            // Clear the wakeup pipe
            pollWrapper.putEventOps(pollWrapper.interruptedIndex(), 0);
            synchronized (interruptLock) {
                pollWrapper.clearInterrupted();
                IOUtil.drain(fd0);
                interruptTriggered = false;
            }
        }
        return numKeysUpdated;
    }

    /**
     * Update the keys whose fd's have been selected by the epoll.
     * Add the ready keys to the ready queue.
     *
     * 其实就是获取了epoll的返回结果：selectedKeys记录准备好的selectionKey,readyOps记录准备好的event
     *
     * 该方法会从通过EPollArrayWrapper pollWrapper 以及 fdToKey( 构建文件描述符-SelectorKeyImpl映射表 )来获取
     * 有事件触发的SelectionKeyImpl对象，然后将SelectionKeyImpl放到selectedKey集合( 有事件触发的selectionKey集合，
     * 可以通过selector.selectedKeys()方法获得 )中，即selectedKeys。并重新设置SelectionKeyImpl中相关的readyOps值。
     * 但是，这里要注意两点：
     * ① 如果SelectionKeyImpl已经存在于selectedKeys集合中，并且发现触发的事件已经存在于readyOps中了，则不会使numKeysUpdated++；
     * 这样会使得我们无法得知该事件的变化。
     * 这点说明了为什么我们要在每次从selectedKey中获取到Selectionkey后，将其从selectedKey集合移除(myConfusion:哪里移出了？)，
     * 就是为了当有事件触发使selectionKey能正确到放入selectedKey集合中，并正确的通知给调用者。

     * 再者，如果不将已经处理的SelectionKey从selectedKey集合中移除，那么下次有新事件到来时，在遍历selectedKey集合时又会遍历到这个SelectionKey，
     * 这个时候就很可能出错了。比如，如果没有在处理完OP_ACCEPT事件后将对应SelectionKey从selectedKey集合移除，那么下次遍历selectedKey集合时，
     * 处理到到该SelectionKey，相应的ServerSocketChannel.accept()将返回一个空(null)的SocketChannel。
     *

     * ② 如果发现channel所发生I/O事件不是当前SelectionKey所感兴趣，则不会将SelectionKeyImpl放入selectedKeys集合中，
     * 也不会使numKeysUpdated++ myConfusion:why?
     */
    private int updateSelectedKeys() {
        int entries = pollWrapper.updated;
        int numKeysUpdated = 0;
        for (int i=0; i<entries; i++) {
            // 1. 获取有事件触发的SelectionKeyImpl对象
            int nextFD = pollWrapper.getDescriptor(i);
            SelectionKeyImpl ski = fdToKey.get(Integer.valueOf(nextFD));

            // ski is null in the case of an interrupt
            if (ski != null) {
                // 2. 将SelectionKeyImpl放到selectedKey集合(区分是否存在)、重新设置SelectionKeyImpl中相关的readyOps值
                // rpos:channel发生的事件，表示某操作已准备好（比如读操作则表示数据已经准备好）
                int rOps = pollWrapper.getEventOps(i);
                if (selectedKeys.contains(ski)) {
                    if (ski.channel.translateAndSetReadyOps(rOps, ski)) {
                        numKeysUpdated++;
                    }
                } else {
                    // myConfusionsv:这里更新的readyOps是给线程做后续操作的吧--是的，在NioEventLoop.run()中处理IO任务
                    ski.channel.translateAndSetReadyOps(rOps, ski);
                    // 如果发现channel所发生I/O事件不是当前SelectionKey所感兴趣，则不会将SelectionKeyImpl放入selectedKeys集合中，
                    //     * 也不会使numKeysUpdated++
                    if ((ski.nioReadyOps() & ski.nioInterestOps()) != 0) {
                        selectedKeys.add(ski);
                        numKeysUpdated++;
                    }
                }
            }
        }
        return numKeysUpdated;
    }

    protected void implClose() throws IOException {
        if (closed)
            return;
        closed = true;

        // prevent further wakeup
        synchronized (interruptLock) {
            interruptTriggered = true;
        }

        FileDispatcherImpl.closeIntFD(fd0);
        FileDispatcherImpl.closeIntFD(fd1);

        pollWrapper.closeEPollFD();
        // it is possible
        selectedKeys = null;

        // Deregister channels
        Iterator<SelectionKey> i = keys.iterator();
        while (i.hasNext()) {
            SelectionKeyImpl ski = (SelectionKeyImpl)i.next();
            deregister(ski);
            SelectableChannel selch = ski.channel();
            if (!selch.isOpen() && !selch.isRegistered())
                ((SelChImpl)selch).kill();
            i.remove();
        }

        fd0 = -1;
        fd1 = -1;
    }

    /**
     * myConfusion:服务端只会有一个channel,只会向selector中注册一次,那就只会有一个fd在epoll中被监听，那是如何与监听多个客户端socket关联起来的呢？
     * --难到是只要有一个客户端向服务端写入了数据，服务端就发现当前fd ready了？
     *
     * 将channel注册到epoll中
     * ① 将channel对应的fd(文件描述符)和对应的SelectionKeyImpl放到fdToKey映射表中。
     * ② 将channel对应的fd(文件描述符)添加到EPollArrayWrapper中，并强制初始化fd的事件为0 ( 强制初始更新事件为0，因为该事件可能存在于之前被取消过的注册中。)
     * ③ 将selectionKey放入到keys集合中。
     * @param ski
     */
    protected void implRegister(SelectionKeyImpl ski) {
        if (closed)
            throw new ClosedSelectorException();
        SelChImpl ch = ski.channel;
        int fd = Integer.valueOf(ch.getFDVal());
        // 将channel对应的fd(文件描述符)和对应的SelectionKeyImpl放到fdToKey映射表中
        fdToKey.put(fd, ski);
        // 将channel对应的fd(文件描述符)添加到EPollArrayWrapper中，并强制初始化fd的事件为0 ( 强制初始更新事件为0，因为该事件可能存在于之前被取消过的注册中。)
        pollWrapper.add(fd);
        // 将selectionKey放入到keys集合中。
        keys.add(ski);
    }

    /**
     *
     * 注销
     * 删除了selector中的某一selectionKey
     *
     * ① 将已经注销的selectionKey从fdToKey( 文件描述与SelectionKeyImpl的映射表 )中移除
     * ② 将selectionKey所代表的channel的文件描述符从EPollArrayWrapper中移除
     * ③ 将selectionKey从keys集合中移除，这样下次selector.select()就不会再将该selectionKey注册到epoll中监听
     * ④ 也会将selectionKey从对应的channel中注销
     * ⑤ 最后如果对应的channel已经关闭并且没有注册其他的selector了，则将该channel关闭完成 的操作后，注销的SelectionKey就不会出现先在keys、selectedKeys以及cancelKeys这3个集合中的任何一个。
     * @param ski
     *
     */
    protected void implDereg(SelectionKeyImpl ski) throws IOException {
        assert (ski.getIndex() >= 0);
        SelChImpl ch = ski.channel;
        int fd = ch.getFDVal();
        // 1. 将已经注销的selectionKey从fdToKey( 文件描述与SelectionKeyImpl的映射表 )中移除
        fdToKey.remove(Integer.valueOf(fd));
        // 2. 将selectionKey所代表的channel的文件描述符从EPollArrayWrapper中移除
        pollWrapper.remove(fd);
        ski.setIndex(-1);
        // 3. 将selectionKey从keys集合中移除，这样下次selector.select()就不会再将该selectionKey注册到epoll中监听 myConfusion:将该selectionKey注册到epoll中监听where?
        keys.remove(ski);
        selectedKeys.remove(ski);
        // 4. 也会将selectionKey从对应的channel中注销--删除了channel.keys中的SelectionKey对象
        deregister((AbstractSelectionKey)ski);
        // 5. 最后如果对应的channel已经关闭并且没有注册其他的selector了，则将该channel关闭完成 的操作后，
        // 注销的SelectionKey就不会出现先在keys、selectedKeys以及cancelKeys这3个集合中的任何一个。
        SelectableChannel selch = ski.channel();
        if (!selch.isOpen() && !selch.isRegistered())
            ((SelChImpl)selch).kill();
    }

    public void putEventOps(SelectionKeyImpl ski, int ops) {
        if (closed)
            throw new ClosedSelectorException();
        SelChImpl ch = ski.channel;
        pollWrapper.setInterest(ch.getFDVal(), ops);
    }

    public Selector wakeup() {
        synchronized (interruptLock) {
            if (!interruptTriggered) {
                pollWrapper.interrupt();
                interruptTriggered = true;
            }
        }
        return this;
    }
}
