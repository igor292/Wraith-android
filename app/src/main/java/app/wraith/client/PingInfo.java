package app.wraith.client;
import org.json.JSONObject;
import java.util.*;
final class PingInfo {
 static String effectiveType(JSONObject profile,JSONObject settings){String type=settings.optString("pingType","proxyGet");JSONObject out=profile.optJSONObject("outbound");return type.equals("tcp")&&out!=null&&Set.of("hysteria","hysteria2","tuic").contains(out.optString("type"))?"proxyGet":type;}
 static String label(String type){switch(type){case "proxyGet":return "Proxy GET";case "proxyHead":return "Proxy HEAD";case "icmp":return "ICMP";default:return "TCP";}}
 static String displayName(String name){return name.replaceFirst("^[\\x{1F1E6}-\\x{1F1FF}]{2}\\s*","");}
 /** A flag supplied in a subscription name should be rendered in the icon slot for every country. */
 static String leadingFlag(String name){
  if(name==null||name.isEmpty())return "";
  int offset=0;while(offset<name.length()&&Character.isWhitespace(name.charAt(offset)))offset++;
  if(offset>=name.length())return "";
  int first=name.codePointAt(offset);
  if(first<0x1F1E6||first>0x1F1FF)return "";
  int secondOffset=offset+Character.charCount(first);if(secondOffset>=name.length())return "";
  int second=name.codePointAt(secondOffset);
  if(second<0x1F1E6||second>0x1F1FF)return "";
  return name.substring(offset,secondOffset+Character.charCount(second));
 }
 static String shortError(String error){String e=error.toLowerCase(Locale.ROOT);if(e.contains("reality"))return L.t("Reality: проверка не пройдена","Reality: verification failed");if(e.contains("timeout")||e.contains("timed out")||e.contains("deadline")||e.contains("after "))return L.t("Нет ответа","No answer");if(e.contains("refused"))return L.t("Соединение отклонено","Connection refused");if(e.contains("certificate")||e.contains("x509"))return L.t("Ошибка сертификата TLS","TLS certificate error");if(e.contains("icmp"))return L.t("ICMP недоступен","ICMP unavailable");return L.t("Не удалось проверить","Could not test");}
 static String explain(String error){if(error.toLowerCase(Locale.ROOT).contains("reality"))return L.t("Сервер не подтвердил Reality. Обновите подписку и проверьте SNI, public key, short ID и время телефона. Проверка подлинности не отключена.","The server did not confirm Reality. Update the subscription and check SNI, public key, short ID and the phone time. Authenticity checks are not disabled.");return error;}
}
