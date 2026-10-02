package io.github.eljah.busradio.core;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.*;
import java.util.*;
import static io.github.eljah.busradio.core.Json.*;
public final class SignedManifest {
 private SignedManifest(){}
 public static Map<String,Object> sign(Model.Manifest m,PrivateKey key)throws GeneralSecurityException{byte[] payload=Json.write(m).getBytes(StandardCharsets.UTF_8);Signature s=Signature.getInstance("Ed25519");s.initSign(key);s.update(payload);return Map.of("payload",Base64.getEncoder().encodeToString(payload),"signature",Base64.getEncoder().encodeToString(s.sign()));}
 public static Model.Manifest verify(Object envelope,String pinnedKey)throws GeneralSecurityException{var m=obj(envelope);byte[] payload=Base64.getDecoder().decode(str(m,"payload"));if(payload.length>4_000_000)throw new IllegalArgumentException("Manifest too large");Signature s=Signature.getInstance("Ed25519");s.initVerify(publicKey(pinnedKey));s.update(payload);if(!s.verify(Base64.getDecoder().decode(str(m,"signature"))))throw new SignatureException("Manifest signature mismatch");return Model.Manifest.from(Json.parse(new String(payload,StandardCharsets.UTF_8)));}
 public static PublicKey publicKey(String key)throws GeneralSecurityException{return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(key)));}
 public static PrivateKey privateKey(String key)throws GeneralSecurityException{return KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(key)));}
}
