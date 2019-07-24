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

import org.openjdk.jol.util.ObjectUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Basic class to walk object graphs.
 *
 * @author Aleksey Shipilev
 */
public class GraphWalker2 {

    private final Set<Object> visited;
    private final Object[] roots;
    private final Collection<GraphVisitor2> visitors;

    public GraphWalker2(Object... roots) {
        this.roots = roots;
        this.visited = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
        this.visitors = new ArrayList<GraphVisitor2>();
    }

    public void addVisitor(GraphVisitor2 v) {
        visitors.add(v);
    }

    public void walk(Predicate<Object> stop) {
        List<GraphPathRecord2> curLayer = new ArrayList<GraphPathRecord2>();
        List<GraphPathRecord2> newLayer = new ArrayList<GraphPathRecord2>();

        int rootId = 1;
        boolean single = (roots.length == 1);
        for (Object root : roots) {
            GraphPathRecord2 e = new GraphPathRecord2(0, root);
            visited.add(root);
            visitObject(e);
            curLayer.add(e);
            rootId++;
        }

        while (!curLayer.isEmpty()) {
            newLayer.clear();
            for (GraphPathRecord2 next : curLayer) {
                for (GraphPathRecord2 ref : peelReferences(next, stop)) {
                    if (visited.add(ref.obj())) {
                        visitObject(ref);
                        newLayer.add(ref);
                    }
                }
            }
            curLayer.clear();
            curLayer.addAll(newLayer);
        }
    }

    private void visitObject(GraphPathRecord2 record) {
        for (GraphVisitor2 v : visitors) {
            v.visit(record);
        }
    }

    private List<GraphPathRecord2> peelReferences(GraphPathRecord2 r,
						  Predicate<Object> stop) {
        Object o = r.obj();

        if (o == null) {
            // Nothing to do here
            return Collections.emptyList();
        }

        if (o.getClass().isArray() && o.getClass().getComponentType().isPrimitive()) {
            // Nothing to do here
            return Collections.emptyList();
        }

        if (stop != null && stop.test(o)) {
            // The caller does not want any references from this
            // object walked.
            return Collections.emptyList();
        }

        List<GraphPathRecord2> result = new ArrayList<GraphPathRecord2>();

        if (o.getClass().isArray()) {
            for (Object e : (Object[]) o) {
                if (e != null) {
                    result.add(new GraphPathRecord2(r.depth() + 1, e));
                }
            }
        } else {
            for (Field f : getAllReferences(o.getClass())) {
                Object e = ObjectUtils.value(o, f);
                if (e != null) {
                    result.add(new GraphPathRecord2(r.depth() + 1, e));
                }
            }
        }

        return result;
    }

    private Collection<Field> getAllReferences(Class<?> klass) {
        List<Field> results = new ArrayList<Field>();

        for (Field f : klass.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            if (f.getType().isPrimitive()) continue;
            results.add(f);
        }

        Class<?> superKlass = klass;
        while ((superKlass = superKlass.getSuperclass()) != null) {
            for (Field f : superKlass.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                if (f.getType().isPrimitive()) continue;
                results.add(f);
            }
        }

        return results;
    }

}
