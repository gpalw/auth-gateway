package dev.liangwen.relay;

import java.net.*;
import java.util.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

@Component
final class SourcePolicy {
    private final Set<String> trusted;
    SourcePolicy(RelaySettings settings) {
        trusted=new HashSet<>();for(String ip:settings.trustedProxyIps())trusted.add(address(ip).getHostAddress());
    }
    String client(HttpServletRequest request) {
        return client(request.getRemoteAddr(),Collections.list(request.getHeaders("X-Forwarded-For")),request.getHeader("Forwarded")!=null);
    }
    String client(String peer,List<String> forwarding,boolean rfcForwarded) {
        String normalized=address(peer).getHostAddress();
        if(rfcForwarded)throw new RelayError(400,"unsupported_forwarding");
        if(forwarding.isEmpty())return normalized;
        if(!trusted.contains(normalized) || forwarding.size()!=1 || forwarding.getFirst().length()>512)throw new RelayError(403,"untrusted_proxy");
        String[] parts=forwarding.getFirst().split(",",-1);
        if(parts.length>10)throw new RelayError(400,"invalid_forwarding");
        for(String part:parts)address(part.trim());
        // Contract: the directly connected trusted proxy MUST append its observed client.
        return address(parts[parts.length-1].trim()).getHostAddress();
    }
    static InetAddress address(String input) {
        if(input==null || input.length()>45 || !(input.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}") || input.matches("[0-9a-fA-F:]+") && input.contains(":")))throw new RelayError(400,"invalid_ip");
        if(!input.contains(":"))for(String octet:input.split("\\."))if(Integer.parseInt(octet)>255 || octet.length()>1 && octet.startsWith("0"))throw new RelayError(400,"invalid_ip");
        try{return InetAddress.getByName(input);}catch(Exception e){throw new RelayError(400,"invalid_ip");}
    }
    static void validate(String cidr){parse(cidr);}
    private record Network(byte[] bytes,int bits){}
    private static Network parse(String cidr){
        try{String[] s=cidr.split("/",-1);if(s.length>2)throw new IllegalArgumentException();byte[] bytes=address(s[0]).getAddress();
            int bits=s.length==2?Integer.parseInt(s[1]):bytes.length*8;if(bits<0 || bits>bytes.length*8)throw new IllegalArgumentException();return new Network(bytes,bits);
        }catch(Exception e){throw new RelayError(400,"invalid_cidr");}
    }
    static boolean permits(String ip,String cidr){
        Network n=parse(cidr);byte[] a=address(ip).getAddress();if(a.length!=n.bytes().length)return false;
        for(int bit=0;bit<n.bits();bit++)if((a[bit/8] & (1<<(7-bit%8)))!=(n.bytes()[bit/8] & (1<<(7-bit%8))))return false;return true;
    }
}
