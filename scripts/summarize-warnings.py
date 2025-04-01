#! /usr/bin/env python3

import fileinput
import re

for line in fileinput.input():
    line = line.rstrip()
    match = re.search(r"""WARNING: A Java agent has been loaded dynamically \(/tmp/jolAgent\d+.jar\)""", line)
    if match:
        print("WARN1")
        continue

    match = re.search(r"""WARNING: If a serviceability tool is in use, please run with -XX:\+EnableDynamicAgentLoading to hide this warning""", line)
    if match:
        print("WARN2")
        continue
    match = re.search(r"""WARNING: If a serviceability tool is not in use, please run with -Djdk.instrument.traceUsage for more information""", line)
    if match:
        print("WARN3")
        continue
    match = re.search(r"""WARNING: Dynamic loading of agents will be disallowed by default in a future release""", line)
    if match:
        print("WARN4")
        continue
    match = re.search(r"""# WARNING: Unable to attach Serviceability Agent. sun.jvm.hotspot.memory.Universe.getNarrowOopBase\(\)""", line)
    if match:
        print("WARN5")
        continue
    match = re.search(r"""WARNING: A terminally deprecated method in sun.misc.Unsafe has been called""", line)
    if match:
        print("WARN6")
        continue
    match = re.search(r"""WARNING: sun.misc.Unsafe::arrayBaseOffset has been called by org.openjdk.jol.vm.HotspotUnsafe \(file:.*.m2/repository/org/openjdk/jol/jol-core/0.9/jol-core-0.9.jar\)""", line)
    if match:
        print("WARN-Unsafe::arrayBaseOffset-called")
        continue
    match = re.search(r"""WARNING: Please consider reporting this to the maintainers of (.*)""", line)
    if match:
        print("WARN-report-%s" % (match.group(1)))
        continue
    match = re.search(r"""WARNING: sun.misc.Unsafe::arrayBaseOffset will be removed in a future release""", line)
    if match:
        print("WARN-Unsafe::arrayBaseOffset-will-be-removed")
        continue
    match = re.search(r"""Boxed math warning, cljol/ubergraph_extras.clj:\d+:\d+ - call: public static java.lang.Number clojure.lang.Numbers.divide\(java.lang.Object,long\).""", line)
    if match:
        print("WARN-BoxedMath1")
        continue
    match = re.search(r"""Boxed math warning, cljol/ubergraph_extras.clj:128:9 - call: public static boolean clojure.lang.Numbers.lt\(long,java.lang.Object\).""", line)
    if match:
        print("WARN-BoxedMath2")
        continue
    match = re.search(r"""# WARNING: Unable to get Instrumentation. Dynamic Attach failed. You may add this JAR as -javaagent manually, or supply -Djdk.attach.allowAttachSelf""", line)
    if match:
        print("WARN7")
        continue
    match = re.search(r"""# WARNING: Unable to attach Serviceability Agent. You can try again with escalated privileges. Two options: a\) use -Djol.tryWithSudo=true to try with sudo; b\) echo 0 | sudo tee /proc/sys/kernel/yama/ptrace_scope""", line)
    if match:
        print("WARN8")
        continue
    match = re.search(r"""WARNING: An illegal reflective access operation has occurred""", line)
    if match:
        print("WARN9")
        continue
    match = re.search(r"""WARNING: Illegal reflective access by org.openjdk.jol.util.ObjectUtils \(file:.*/.m2/repository/org/openjdk/jol/jol-core/0.9/jol-core-0.9.jar\) to field (.*)""", line)
    if match:
        print("WARN10-%s" % (match.group(1)))
        continue
    match = re.search(r"""WARNING: Use --illegal-access=warn to enable warnings of further illegal reflective access operations""", line)
    if match:
        print("WARN11")
        continue
    match = re.search(r"""WARNING: All illegal access operations will be denied in a future release""", line)
    if match:
        print("WARN12")
        continue
#    match = re.search(r"""
#    """, line)
#    if match:
#        print("WARN")
#        continue
    print(line)


