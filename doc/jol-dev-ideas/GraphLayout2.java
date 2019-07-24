/*
 * Copyright (c) 2012, 2013, Oracle and/or its affiliates. All rights reserved.
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
package org.openjdk.jol.info;

import org.openjdk.jol.util.Multiset;
import org.openjdk.jol.util.ObjectUtils;
import org.openjdk.jol.vm.VM;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

/**
 * Holds the object graph layout info.
 */
public class GraphLayout2 {

    /**
     * Parse the object graph starting from the given instances.
     *
     * See GraphLayout.parseInstance() documentation for notes on the
     * few guarantees that can be made by this method if other threads
     * are modifying references between parsed objects.
     *
     * This method puts the collection of objects found as keys into
     * an IdentityHashMap.  The value in the map corresponding to each
     * object is a GraphPathRecord2 describing the object.  You can get
     * that IdentityHashMap using the objectsFound() method.
     *
     * If no parsed objects are modified, at least not in the
     * references that exist between them, during this method's
     * execution, the set of objects that are the keys of the
     * IdentityHashMap created should always be correct.
     *
     * TBD correct this paragraph if there is no addresses field any
     * more: The address map returned by the addresses() method should
     * be correct if no garbage collection occurred during this
     * method's execution, but there is nothing returned to indicate
     * whether they are complete or not.  This is similar to what can
     * happen in the GraphLayout.parseInstance() method, but for this
     * method at least the IdentityHashMap should have the correct set
     * of objects as keys, and you may try calling the
     * addressSnapshot() method on this GraphLayout instance to have a
     * better chance of getting a correct address map.
     *
     * The addressSnapshot() method allocates much less memory than
     * this method does, and is thus less likely to trigger a garbage
     * collection run.
     *
     * @param stop null gives the default behavior of walking all references of all objects.  By supplying a Predicate, every time the walk reaches an object o, it will call the stop.test(o) predicate, and avoid following any references out of o to other objects.
     * @param roots root instances to start from
     * @return object graph
     */
    public static GraphLayout2 parseInstanceIds(Predicate<Object> stop,
						Object... roots) {
        if (roots == null) {
            throw new IllegalArgumentException("Roots are null");
        }
        for (Object root : roots) {
            if (root == null) {
                throw new IllegalArgumentException("Some root is null");
            }
        }
        GraphLayout2 data = new GraphLayout2(roots);
        GraphWalker2 walker = new GraphWalker2(roots);
        walker.addVisitor(data.visitorById());
        walker.walk(stop);
	/*
        if (data.createAddressSnapshot(1)) {
            data.createAddressMap();
        }
	*/
        return data;
    }

    /**
     * Try to create a consistent snapshot of object addresses.  Very
     * little memory -- a constant amount, regardless of the size of
     * the object graph -- should be allocated by this method, to
     * minimize its chances of triggering a garbage collection while
     * it executes, and maximize the likelihood that the set of
     * addresses returned represent a consistent set.  No other
     * attempt is made to prevent GC from occurring while this method
     * executes, so even though it does do one pass over the objects
     * to record all of their current addresses, and then another pass
     * to see if any of them have moved, retrying that pair of steps
     * up to maxAttempts times if anything has moved, it is still
     * possible (though it seems unlikely) that this method could
     * return true (indicating success) even though the addresses do
     * not represent a consistent snapshot.
     *
     * @param maxAttempts Maximum number of times to attempt creating a snapshot before returning false.
     * @return true for success, false for failure
     */
    public boolean createAddressSnapshot(int maxAttempts) {
        boolean attemptSucceeded = false;
        int attempts = 0;
        do {
            ++attempts;
            recordAddresses();
            Exception e = validateAddresses();
            if (e == null) {
                attemptSucceeded = true;
                break;
            }
        } while (attempts <= maxAttempts);
	/*
        if (attemptSucceeded) {
            createAddressMap();
        }
	*/
        return attemptSucceeded;
    }

    private static final Comparator<Class<?>> CLASS_COMPARATOR = new Comparator<Class<?>>() {
        @Override
        public int compare(Class<?> o1, Class<?> o2) {
            return o1.getName().compareTo(o2.getName());
        }
    };

    private final IdentityHashMap<Object, GraphPathRecord2> objectsFound = new IdentityHashMap<Object, GraphPathRecord2>();

    private final String description;
    private long totalCount;
    private long totalSize;

    public GraphLayout2(Object... roots) {
        StringBuilder sb = new StringBuilder();
        boolean isFirst = true;
        for (Object root : roots) {
            if (isFirst) {
                isFirst = false;
            } else {
                sb.append(", ");
            }
            sb.append(String.format("%s@%xd", root.getClass().getName(), System.identityHashCode(root)));
        }
        this.description = sb.toString();
    }

    private GraphVisitor2 visitorById() {
        return new GraphVisitor2() {
            @Override
            public void visit(GraphPathRecord2 gpr) {
                addRecordById(gpr);
            }
        };
    }

    private void addRecordById(GraphPathRecord2 gpr) {
        objectsFound.put(gpr.obj(), gpr);
        totalCount++;
        try {
            long size = gpr.size();
            totalSize += size;
        } catch (Exception e) {
	    /* nothing to do here */
        }
    }

    public void recordAddresses() {
        /* Iterate through objectsFound, recording the address of each
         * object once.  This method should allocate only a small
         * constant amount of memory, to maximize the chances that it
         * does not trigger a GC while it executes, and thus the
         * addresses recorded represent a consistent 'snapshot' across
         * all objects at one point in time. */
        BiConsumer<Object, GraphPathRecord2> recordOneAddress =
            new BiConsumer() {
                @Override
                public void accept(Object o, Object gprArg) {
                    GraphPathRecord2 gpr = (GraphPathRecord2) gprArg;
                    gpr.recordAddress();
                }
            };
        objectsFound.forEach(recordOneAddress);
    }

    public Exception validateAddresses() {
        /* Same comments apply here as for method
         * recordAddresses(). */
        BiConsumer<Object, GraphPathRecord2> validateOneAddress =
            new BiConsumer() {
                @Override
                public void accept(Object o, Object gprArg) {
                    GraphPathRecord2 gpr = (GraphPathRecord2) gprArg;
                    if (o != gpr.obj()) {
                        String msg = "Found GraphPathRecord2 with key object " + o.getClass() + " different than the object " + gpr.obj().getClass() + " in the GraphPathRecord2";
                        throw new IllegalArgumentException(msg);
                    }
                    long curAddress = VM.current().addressOf(o);
                    if (curAddress != gpr.address()) {
                        String msg = "Found GraphPathRecord2 with current address " + curAddress + " different than recorded address " + gpr.address() + " object " + gpr.obj().getClass();
                        throw new IllegalArgumentException(msg);
                    }
                }
            };
        try {
            objectsFound.forEach(validateOneAddress);
        } catch (Exception e) {
            return e;
        }
        return null;
    }

    /*
    public void createAddressMap() {
        BiConsumer<Object, GraphPathRecord2> addOneAddress =
            new BiConsumer() {
                @Override
                public void accept(Object o, Object gprArg) {
                    GraphPathRecord2 gpr = (GraphPathRecord2) gprArg;
                    addresses.put(gpr.address(), gpr);
                }
            };
        addresses.clear();
        objectsFound.forEach(addOneAddress);
    }
    */

    /**
     * Answer the total instance count
     *
     * @return total instance count
     */
    public long totalCount() {
        return totalCount;
    }

    /**
     * Answer the total instance footprint
     *
     * @return total instance footprint, bytes
     */
    public long totalSize() {
        return totalSize;
    }

    /**
     * Answer the IdentityHashMap of object references to their
     * associated GraphPathRecord2.
     *
     * @return IdentityHashMap from object identities to GraphPathRecord2 objects with some extra info about those objects.
     * @see #record(long)
     */
    public IdentityHashMap<Object, GraphPathRecord2> objectsFound() {
        return objectsFound;
    }

}
