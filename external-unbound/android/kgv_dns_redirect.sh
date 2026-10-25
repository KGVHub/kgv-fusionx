#!/system/bin/sh
# KGV FusionX: send every app's DNS to the on-device Unbound resolver.
#
# Plain DNS (port 53) leaving the phone is redirected to Unbound on port 5353,
# except Unbound's own queries (it runs as the dns user). If Unbound stops
# answering, or the network blocks direct DNS so recursion can't work, the
# redirect is lifted and the phone uses the network's DNS until it recovers,
# so a strict hostel or office network never leaves you without internet.
#
# usage: kgv_dns_redirect start   (init's kgv_dns service; follows persist.kgv.dns.mode)

CHAIN=kgv_dns
DNS_UID=1051

rules() {
    for ipt in iptables ip6tables; do
        $ipt -w -t nat -N $CHAIN 2>/dev/null
        $ipt -w -t nat -F $CHAIN
        $ipt -w -t nat -A $CHAIN -m owner --uid-owner $DNS_UID -j RETURN
        $ipt -w -t nat -A $CHAIN -o lo -j RETURN
        $ipt -w -t nat -A $CHAIN -p udp --dport 53 -j REDIRECT --to-ports 5353
        $ipt -w -t nat -A $CHAIN -p tcp --dport 53 -j REDIRECT --to-ports 5353
        $ipt -w -t nat -C OUTPUT -j $CHAIN 2>/dev/null || $ipt -w -t nat -I OUTPUT -j $CHAIN
    done
}

unrules() {
    for ipt in iptables ip6tables; do
        $ipt -w -t nat -D OUTPUT -j $CHAIN 2>/dev/null
        $ipt -w -t nat -F $CHAIN 2>/dev/null
        $ipt -w -t nat -X $CHAIN 2>/dev/null
    done
}

# Unbound listens, and a root server answers over TCP port 53 (if the network
# allows that, it allows recursion too). Forward mode only needs Unbound up.
healthy() {
    nc -w 3 127.0.0.1 5353 </dev/null >/dev/null 2>&1 || return 1
    [ "$mode" = recursive ] || return 0
    for root in 198.41.0.4 199.9.14.201 192.33.4.12 2001:503:ba3e::2:30; do
        nc -w 3 $root 53 </dev/null >/dev/null 2>&1 && return 0
    done
    return 1
}

DIR=/data/misc/kgv_unbound

# Seed the trust anchor and write the mode's forwarding settings for Unbound.
prepare() {
    [ -s $DIR/root.key ] || cp /system_ext/etc/unbound/root.key $DIR/root.key
    if [ "$1" = tls ]; then
        # Android's CA store as one bundle, to check Quad9's certificate.
        cat /system/etc/security/cacerts/* > $DIR/ca.pem
        cat > $DIR/forward.conf <<CONF
server:
    tls-cert-bundle: "$DIR/ca.pem"
forward-zone:
    name: "."
    forward-tls-upstream: yes
    forward-addr: 9.9.9.9@853#dns.quad9.net
    forward-addr: 149.112.112.112@853#dns.quad9.net
    forward-addr: 2620:fe::fe@853#dns.quad9.net
CONF
    elif [ "$1" = custom ]; then
        # The user's own servers; Unbound still caches and validates DNSSEC.
        {
            echo 'forward-zone:'
            echo '    name: "."'
            for ip in $(getprop persist.kgv.dns.servers | tr ',' ' '); do
                echo "    forward-addr: $ip"
            done
        } > $DIR/forward.conf
    else
        : > $DIR/forward.conf
    fi
    chown dns:dns $DIR/root.key $DIR/forward.conf $DIR/ca.pem 2>/dev/null
    chmod 0660 $DIR/root.key $DIR/forward.conf 2>/dev/null
}

case "$1" in
    start)
        mode=$(getprop persist.kgv.dns.mode)
        [ -z "$mode" ] && mode=recursive
        [ "$mode" = custom ] && [ -z "$(getprop persist.kgv.dns.servers)" ] && mode=recursive
        if [ "$mode" = off ]; then
            unrules
            setprop kgv.dns.ready off
            setprop kgv.dns.state off
            exit 0
        fi
        prepare $mode
        setprop kgv.dns.ready "$mode.$(date +%s)"   # init (re)starts Unbound
        sleep 2
        state=
        while true; do
            if healthy; then
                [ "$state" = active ] || { rules; state=active; setprop kgv.dns.state active; }
            else
                [ "$state" = fallback ] || { unrules; state=fallback; setprop kgv.dns.state fallback; }
            fi
            sleep 30
        done
        ;;
esac
