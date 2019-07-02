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


With the version of dot below, installed on the given version of
Ubuntu desktop Linux, only the first two files give an error when
running dot on them.  The other 4 cause no errors, and seem to
generate PDF files.

```
$ dot -V
dot - graphviz version 2.38.0 (20140413.2041)

$ lsb_release -a
No LSB modules are available.
Distributor ID:	Ubuntu
Description:	Ubuntu 16.04.6 LTS
Release:	16.04
Codename:	xenial
```

Running on macOS 10.13.6, I get an error running the version of dot
shown below on all 6 files.  That version of dot was installed via
`sudo port install graphviz`.

```bash
$ dot -V
dot - graphviz version 2.40.1 (20161225.0304)

$ uname -a
Darwin JAFINGER-M-W0SR 17.7.0 Darwin Kernel Version 17.7.0: Wed Apr 24 21:17:24 PDT 2019; root:xnu-4570.71.45~1/RELEASE_X86_64 x86_64
```
