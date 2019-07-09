//package org.openjdk.jol.samples;

import java.util.IdentityHashMap;
import org.openjdk.jol.info.ClassLayout;
import org.openjdk.jol.vm.VM;

import static java.lang.System.out;

public class MaybeBug {

    public static boolean showSizes(Object obj) {
	Class c = obj.getClass();

        ClassLayout parsedInst = ClassLayout.parseInstance(obj);
        ClassLayout parsedCls = ClassLayout.parseClass(c);
	long vmSizeOf = VM.current().sizeOf(obj);
	long sizeFromInst = parsedInst.instanceSize();
	long sizeFromCls = parsedCls.instanceSize();

	out.println("\n------------------------------------------------------------");
	out.println("obj= " + obj);
	out.println("obj.getClass()= " + c);
	out.println("\ntoPrintable of parseInstance ret value:");
	out.println(parsedInst.toPrintable());

	out.println("\ntoPrintable of parseClass ret value:");
	out.println(parsedCls.toPrintable());

	out.println("obj= " + obj);
	out.println("obj.getClass()= " + c);
	out.println("VM.current().sizeOf(obj)= " + vmSizeOf);
	out.println("parsedInst.instanceSize()= " + sizeFromInst);
	out.println("parsedCls.instanceSize()= " + sizeFromCls);
	return ((vmSizeOf == sizeFromInst) && (vmSizeOf == sizeFromCls));
    }

    public static void main(String[] args) throws Exception {
	StringBuilder s = new StringBuilder();
	String str = "bar";
	boolean good;
	IdentityHashMap ihm = new IdentityHashMap();

        out.println(VM.current().details());

	final Long i = new Long(5);
	good = showSizes(i);
	s.append("\n" + (good ? "ok " : "bad") + " " + i.getClass() + " obj=" + i);

	good = showSizes(str);
	s.append("\n" + (good ? "ok " : "bad") + " " + str.getClass() + " obj=" + str);

	good = showSizes(ihm);
	s.append("\n" + (good ? "ok " : "bad") + " " + ihm.getClass() + " obj=" + ihm);

	final Class c = Class.forName("java.lang.Class");
	good = showSizes(c);
	s.append("\n" + (good ? "ok " : "bad") + " " + c.getClass() + " obj=" + c);

	final Class c2 = Class.forName("java.lang.Long");
	good = showSizes(c2);
	s.append("\n" + (good ? "ok " : "bad") + " " + c2.getClass() + " obj=" + c2);

	final Class c3 = Class.forName("java.lang.String");
	good = showSizes(c3);
	s.append("\n" + (good ? "ok " : "bad") + " " + c3.getClass() + " obj=" + c3);

	final Class c4 = Class.forName("java.util.IdentityHashMap");
	good = showSizes(c4);
	s.append("\n" + (good ? "ok " : "bad") + " " + c4.getClass() + " obj=" + c4);

	out.println("\n------------------------------------------------------------");
	out.println(s.toString());
    }
}
