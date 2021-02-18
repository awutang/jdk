/*
 * Copyright (c) 2000, 2012, Oracle and/or its affiliates. All rights reserved.
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


/**
 * An implementation of SelectionKey for Solaris.
 */

public class SelectionKeyImpl
    extends AbstractSelectionKey
{

    final SelChImpl channel;                            // package-private
    public final SelectorImpl selector;

    // Index for a pollfd array in Selector that this key is registered with
    private int index;

    // readyOps与interestOps有啥关系？--监控即将发生、已经发生（比如读操作，channel中接收到数据后，就更新readyOps让后续线程从channel中读数据？
    // --是的，interestOps是epoll中需要对某一selectionKey和事件进行监听的描述，
    // 当epoll中监听到某一fd(channel->fd)准备好后，更新readyOps(应用可以得到是什么操作准备好了从而进行相应操作)）。
    // 这就使得应用层与linux系统调用联系起来了！！！
    //  -- interestOps表示需要监听的网络操作事件 myConfusionsv:在哪里监听的？Future?--epoll
    /**SelectionKey中维护着两个很重要的属性：interestOps、readyOps.
     * interestOps是我们希望Selector监听Channel的哪些事件。我们将我们感兴趣的事件设置到该字段，
     * 这样在epoll_wait操作时，当发现该Channel有我们所感兴趣的事件发生时，就会将我们感兴趣的事件再设置到readyOps中，
     * 这样我们就能得知是哪些事件发生了，NioEventLoop中以做相应处理。*/
    private volatile int interestOps;
    private int readyOps;

    SelectionKeyImpl(SelChImpl ch, SelectorImpl sel) {
        channel = ch;
        selector = sel;
    }

    public SelectableChannel channel() {
        return (SelectableChannel)channel;
    }

    public Selector selector() {
        return selector;
    }

    int getIndex() {                                    // package-private
        return index;
    }

    void setIndex(int i) {                              // package-private
        index = i;
    }

    private void ensureValid() {
        if (!isValid())
            throw new CancelledKeyException();
    }

    public int interestOps() {
        ensureValid();
        return interestOps;
    }

    public SelectionKey interestOps(int ops) {
        ensureValid();
        return nioInterestOps(ops);
    }

    public int readyOps() {
        ensureValid();
        return readyOps;
    }

    // The nio versions of these operations do not care if a key
    // has been invalidated. They are for internal use by nio code.

    public void nioReadyOps(int ops) {
        readyOps = ops;
    }

    public int nioReadyOps() {
        return readyOps;
    }

    /**
     * a) 会将注册的感兴趣的事件和其对应的文件描述存储到EPollArrayWrapper对象的eventsLow或eventsHigh中，这是给底层实现epoll_wait时使用的。
     * b) 同时该操作还会将设置SelectionKey的interestOps字段，这是给我们程序员获取使用的
     * @param ops
     * @return
     */
    public SelectionKey nioInterestOps(int ops) {
        if ((ops & ~channel().validOps()) != 0)
            throw new IllegalArgumentException();
        channel.translateAndSetInterestOps(ops, this);
        interestOps = ops;
        return this;
    }

    public int nioInterestOps() {
        return interestOps;
    }

}
