# external/unbound for KGV FusionX

The Android build files for Unbound 1.26.2. Only these are kept here; the
upstream source (31 MB) is downloaded:

    curl -LO https://nlnetlabs.nl/downloads/unbound/unbound-1.26.2.tar.gz
    echo "$(curl -sL https://nlnetlabs.nl/downloads/unbound/unbound-1.26.2.tar.gz.sha256)  unbound-1.26.2.tar.gz" | sha256sum -c
    mkdir -p ../fusionx/external/unbound
    tar xzf unbound-1.26.2.tar.gz --strip-components=1 -C ../fusionx/external/unbound
    cp -r ~/claude/fusionx-port/external-unbound/{Android.bp,config.h,unbound.rc,android,dnstap,dnscrypt} \
        ../fusionx/external/unbound/

- config.h: from `./configure --without-pthreads --without-libevent
  --disable-gost --disable-ed448`, with OpenSSL 3-only and SysV shm features
  turned off for BoringSSL and bionic.
- android/unbound.conf: recursive, DNSSEC-validating, on 127.0.0.1:5353.
- android/kgv_dns_redirect.sh: iptables redirect of all port-53 DNS to it,
  with a fallback to the network's DNS when recursion is blocked.
- SELinux: device/xiaomi/mido/sepolicy/private/kgv_*.te.
