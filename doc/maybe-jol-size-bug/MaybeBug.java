//package org.openjdk.jol.samples;

import org.openjdk.jol.info.ClassLayout;
import org.openjdk.jol.vm.VM;

import static java.lang.System.out;

public class MaybeBug {

    public static void showSizes(Object obj) {
	Class c = obj.getClass();

        ClassLayout parsedInst = ClassLayout.parseInstance(obj);
        ClassLayout parsedCls = ClassLayout.parseClass(c);
	long vmSizeOf = VM.current().sizeOf(obj);
	long sizeFromInst = parsedInst.instanceSize();
	long sizeFromCls = parsedCls.instanceSize();

	out.println("toPrintable of parseInstance ret value:");
	out.println(parsedInst.toPrintable());

	out.println("\ntoPrintable of parseClass ret value:");
	out.println(parsedCls.toPrintable());

	out.println("\nclass: " + c);
	out.println("VM.current().sizeOf(obj)= " + vmSizeOf);
	out.println("parsedInst.instanceSize()= " + sizeFromInst);
	out.println("parsedCls.instanceSize()= " + sizeFromCls);
    }

    public static void main(String[] args) throws Exception {
        out.println(VM.current().details());

	final Long i = new Long(5);
	final Class c = Class.forName("java.lang.Class");

	showSizes(i);
	showSizes(c);
    }
}
