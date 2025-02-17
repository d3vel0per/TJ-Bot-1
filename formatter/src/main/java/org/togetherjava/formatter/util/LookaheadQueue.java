package org.togetherjava.formatter.util;

import java.util.Queue;

public interface LookaheadQueue<E> extends Queue<E> {
    /**
     * Peeks into the "future", peek(0) would be the equivalent to peek()
     *
     * @author illuminator3
     */
    E peek(int n);
}
