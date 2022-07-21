ARG LLVM_VERSION=13

FROM debian:bullseye

ARG LLVM_VERSION

RUN apt-get update && apt-get install --no-install-recommends -y ca-certificates gnupg wget && \
	echo "deb https://repo.scala-sbt.org/scalasbt/debian /" > /etc/apt/sources.list.d/sbt.list && \
	echo "deb https://deb.debian.org/debian bullseye-backports main" > /etc/apt/sources.list.d/backports.list && \
	wget -O - "https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x2EE0EA64E40A89B84B2DF73499E82A75642AC823" | apt-key add && \
	wget -O - https://apt.llvm.org/llvm-snapshot.gpg.key|apt-key add - && \
	echo "deb http://apt.llvm.org/$(sed -n 's/VERSION_CODENAME=\(.*\)/\1/p' /etc/os-release)/ llvm-toolchain-$(sed -n 's/VERSION_CODENAME=\(.*\)/\1/p' /etc/os-release)-${LLVM_VERSION} main" > /etc/apt/sources.list.d/llvm.list && \
	apt-get update && apt-get dist-upgrade -y && apt-get install --no-install-recommends -y \
	bash bash-completion \
	build-essential bison flex gawk \
	libgmp-dev libmpfr-dev libmpc-dev zlib1g-dev \
	libexpat1-dev \
	sbt \
	texinfo gengetopt \
	cmake/bullseye-backports cmake-data/bullseye-backports \
	pkg-config \
	python3 python3-dev patch diffstat subversion git ssh-client \
	device-tree-compiler \
	verilator \
	bc \
	libglib2.0-dev libpixman-1-dev default-jre-headless \
	automake \
	libtool \
	llvm-${LLVM_VERSION} llvm-${LLVM_VERSION}-dev clang-${LLVM_VERSION} clangd-${LLVM_VERSION} lld-${LLVM_VERSION} libc++-${LLVM_VERSION}-dev libc++abi-${LLVM_VERSION}-dev ninja-build cmake-curses-gui/bullseye-backports libomp-${LLVM_VERSION}-dev libunwind-${LLVM_VERSION}-dev \
	graphviz \
	bear \
	gdb lldb-${LLVM_VERSION} \
	locales \
	libtinfo5 \
	libedit-dev libxml2-dev liblzma-dev swig \
	libusb-1.0-0-dev && \
	\
	echo "en_US.UTF-8 UTF-8" > /etc/locale.gen && locale-gen --purge &&\
	\
	mkdir -p /usr/share/man/man1 && \
	echo package dash/sh boolean false|debconf-set-selections && dpkg-reconfigure -fnoninteractive dash && \
	rm -rf /usr/share/man/man1 && \
	\
	addgroup --gid 1000 docker && \
	adduser --uid 1000 --ingroup docker --home /home/docker --shell /bin/bash --disabled-password --gecos "" docker && \
	wget -O - https://github.com/boxboat/fixuid/releases/download/v0.5/fixuid-0.5-linux-amd64.tar.gz | tar -C /usr/local/bin -xzf - && \
	chown root:root /usr/local/bin/fixuid && \
	chmod 4755 /usr/local/bin/fixuid && \
	mkdir -p /etc/fixuid && \
	printf "user: docker\ngroup: docker\n" > /etc/fixuid/config.yml

#RUN apt-get install -y --no-install-recommends libtinfo5

ENV LANG en_US.UTF-8
ENV LANGUAGE en_US:en  
ENV LC_ALL en_US.UTF-8

RUN rm -rf /var/lib/apt/lists/* 

USER docker:docker
ENTRYPOINT ["fixuid"]

ENV SHELL /bin/bash
ENV MAKEFLAGS -j12
ENV VERILATOR_THREADS 12
ENV GDB_TARGET_FLAGS_EXTRA --without-python
#--with-python=python3

WORKDIR /chipyard

CMD /bin/bash

