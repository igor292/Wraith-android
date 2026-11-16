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
 static String shortError(String error){String e=error.toLowerCase(Locale.ROOT);if(e.contains("reality"))return "Reality: проверка не пройдена";if(e.contains("timeout")||e.contains("timed out")||e.contains("deadline")||e.contains("after "))return "Нет ответа";if(e.contains("refused"))return "Соединение отклонено";if(e.contains("certificate")||e.contains("x509"))return "Ошибка сертификата TLS";if(e.contains("icmp"))return "ICMP недоступен";return "Не удалось проверить";}
 static String explain(String error){if(error.toLowerCase(Locale.ROOT).contains("reality"))return "Сервер не подтвердил Reality. Обновите подписку и проверьте SNI, public key, short ID и время телефона. Проверка подлинности не отключена.";return error;}
}
