The following files were found to cause errors with the following
`dot` command:

```bash
j=name of the file
k=`basename $F .dot`.pdf
dot -Tpdf $F -o $G
```

with this version of dot shown below, installed on the given version
of Ubuntu desktop Linux via the command `sudo apt-get install
graphviz`:

```
$ dot -V
dot - graphviz version 2.40.1 (20161225.0304)

$ lsb_release -a
No LSB modules are available.
Distributor ID:	Ubuntu
Description:	Ubuntu 18.04.2 LTS
Release:	18.04
Codename:	bionic
```

```
s00-Linux-4.15.0-54-jdk-Oracle-11.0.3-clj-1.10.1.dot
s00-Linux-4.15.0-54-jdk-Oracle-1.8.0_192-clj-1.10.1.dot
s09-30-Linux-4.15.0-54-jdk-Oracle-11.0.3-clj-1.10.1.dot
s09-30-Linux-4.15.0-54-jdk-Oracle-1.8.0_192-clj-1.10.1.dot
s09-31-Linux-4.15.0-54-jdk-Oracle-11.0.3-clj-1.10.1.dot
s09-31-Linux-4.15.0-54-jdk-Oracle-1.8.0_192-clj-1.10.1.dot
```
