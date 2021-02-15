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
import java.net.SocketException;
import java.util.*;


/**
 * Base Selector implementation class.
 */

public abstract class SelectorImpl
    extends AbstractSelector
{

    // The set of keys with data ready for an operation
    protected Set<SelectionKey> selectedKeys;

    // The set of keys registered with this Selector
    protected HashSet<SelectionKey> keys;

    // Public views of the key sets
    private Set<SelectionKey> publicKeys;             // Immutable
    private Set<SelectionKey> publicSelectedKeys;     // Removal allowed, but not addition

    protected SelectorImpl(SelectorProvider sp) {
        super(sp);
        keys = new HashSet<SelectionKey>();
        selectedKeys = new HashSet<SelectionKey>();
        if (Util.atBugLevel("1.4")) {
            publicKeys = keys;
            publicSelectedKeys = selectedKeys;
        } else {
            publicKeys = Collections.unmodifiableSet(keys);
            publicSelectedKeys = Util.ungrowableSet(selectedKeys);
        }
    }

    public Set<SelectionKey> keys() {
        if (!isOpen() && !Util.atBugLevel("1.4"))
            throw new ClosedSelectorException();
        return publicKeys;
    }

    public Set<SelectionKey> selectedKeys() {
        if (!isOpen() && !Util.atBugLevel("1.4"))
            throw new ClosedSelectorException();
        return publicSelectedKeys;
    }

    protected abstract int doSelect(long timeout) throws IOException;

    private int lockAndDoSelect(long timeout) throws IOException {
        synchronized (this) {
            if (!isOpen())
                throw new ClosedSelectorException();
            synchronized (publicKeys) {
                synchronized (publicSelectedKeys) {
                    // myConfusionsv:epoll? or kqueue?--linux用epoll,mac用kqueue
                    return doSelect(timeout);
                }
            }
        }
    }

    /**
     * 该方法和select()类似，该方法也会导致阻塞直到至少一个channel被选择(即，该channel注册的事件发生了)为止，
     * 除非下面3种情况任意一种发生：a) 设置的超时时间到达；b) 当前线程发生中断；c) selector的wakeup方法被调用
     * @param timeout
     * @return
     * @throws IOException
     */
    public int select(long timeout)
        throws IOException
    {
        if (timeout < 0)
            throw new IllegalArgumentException("Negative timeout");
        return lockAndDoSelect((timeout == 0) ? -1 : timeout);
    }

    /**
     * 该方法会一直阻塞直到至少一个channel被选择(即，该channel注册的事件发生了)为止，
     * 除非当前线程发生中断或者selector的wakeup方法被调用
     * @return
     * @throws IOException
     */
    public int select() throws IOException {
        return select(0);
    }

    /**
     * 该方法不会发生阻塞，如果没有一个channel被选择也会立即返回。主要是超时时间为0所以可以不用等待就返回
     * @return
     * @throws IOException
     */
    public int selectNow() throws IOException {
        return lockAndDoSelect(0);
    }

    public void implCloseSelector() throws IOException {
        wakeup();
        synchronized (this) {
            synchronized (publicKeys) {
                synchronized (publicSelectedKeys) {
                    implClose();
                }
            }
        }
    }

    protected abstract void implClose() throws IOException;

    public void putEventOps(SelectionKeyImpl sk, int ops) { }

    /**
     * 将channel注册到selector时，创建selectionKey时设置channel与selector，并且还要设置感兴趣的操作事件
     * @param ch
     * @param ops 如果是0(不在几个操作位范围中，则表示仅仅只做注册动作)
     * @param attachment
     * @return
     */
    protected final SelectionKey register(AbstractSelectableChannel ch,
                                          int ops,
                                          Object attachment)
    {
        if (!(ch instanceof SelChImpl))
            throw new IllegalSelectorException();
        // 1. 构建代表channel和selector间关系的SelectionKey对象
        SelectionKeyImpl k = new SelectionKeyImpl((SelChImpl)ch, this);
        k.attach(attachment);
        synchronized (publicKeys) {
            // 2. 将channel注册到epoll中
            implRegister(k);
        }
        // 3.a) 会将注册的感兴趣的事件和其对应的文件描述存储到EPollArrayWrapper对象的eventsLow或eventsHigh中，这是给底层实现epoll_wait时使用的。
        // b) 同时该操作还会将设置SelectionKey的interestOps字段，这是给我们程序员获取使用的
        k.interestOps(ops);
        return k;
    }

    protected abstract void implRegister(SelectionKeyImpl ski);

    /**
     * 从cancelledKeys集合中依次取出注销的SelectionKey，执行注销操作，
     * 将处理后的SelectionKey从cancelledKeys集合中移除。
     * 执行processDeregisterQueue()后cancelledKeys集合会为空
     * @throws IOException
     */
    void processDeregisterQueue() throws IOException {
        // Precondition: Synchronized on this, keys, and selectedKeys
        Set<SelectionKey> cks = cancelledKeys();
        synchronized (cks) {
            if (!cks.isEmpty()) {
                Iterator<SelectionKey> i = cks.iterator();
                while (i.hasNext()) {
                    SelectionKeyImpl ski = (SelectionKeyImpl)i.next();
                    try {
                        // 注销操作
                        implDereg(ski);
                    } catch (SocketException se) {
                        throw new IOException("Error deregistering key", se);
                    } finally {
                        i.remove();
                    }
                }
            }
        }
    }

    protected abstract void implDereg(SelectionKeyImpl ski) throws IOException;

    abstract public Selector wakeup();

}
