package app.wraith.client;
import org.json.JSONObject;
import java.util.*;
final class PingInfo {
 static String effectiveType(JSONObject profile,JSONObject settings){String type=settings.optString("pingType","proxyGet");JSONObject out=profile.optJSONObject("outbound");return type.equals("tcp")&&out!=null&&Set.of("hysteria","hysteria2","tuic").contains(out.optString("type"))?"proxyGet":type;}
 static String label(String type){switch(type){case "proxyGet":return "Proxy GET";case "proxyHead":return "Proxy HEAD";case "icmp":return "ICMP";default:return "TCP";}}
 static String displayName(String name){return name.replaceFirst("^[\\x{1F1E6}-\\x{1F1FF}]{2}\\s*","");}
 /** A flag supplied in a subscription name should be rendered in the icon slot for every country. */
 private static boolean regional(int cp){return cp>=0x1F1E6&&cp<=0x1F1FF;}
 private static boolean emojiBase(int cp){return (cp>=0x1F300&&cp<=0x1FAFF)||(cp>=0x2600&&cp<=0x27BF)||(cp>=0x1F000&&cp<=0x1F2FF&&!regional(cp));}
 /** The first emoji of a name: a flag (two regional indicators) or an emoji with its modifiers and ZWJ parts; "" when none. */
 static String firstEmoji(String name){
  if(name==null)return "";
  for(int i=0;i<name.length();){
   int cp=name.codePointAt(i);int n=Character.charCount(cp);
   if(regional(cp)&&i+n<name.length()&&regional(name.codePointAt(i+n)))return name.substring(i,i+n+Character.charCount(name.codePointAt(i+n)));
   if(emojiBase(cp)){
    int end=i+n;
    while(end<name.length()){int c=name.codePointAt(end);
     if(c==0xFE0F||(c>=0x1F3FB&&c<=0x1F3FF)){end+=Character.charCount(c);continue;}
     if(c==0x200D&&end+1<name.length()){int next=name.codePointAt(end+1);end+=1+Character.charCount(next);continue;}
     break;}
    return name.substring(i,end);
   }
   i+=n;
  }
  return "";
 }
 static boolean isFlag(String s){return s!=null&&s.codePointCount(0,s.length())==2&&regional(s.codePointAt(0))&&regional(s.codePointBefore(s.length()));}
 static boolean isEmoji(String s){return s!=null&&!s.isEmpty()&&(regional(s.codePointAt(0))||emojiBase(s.codePointAt(0)));}
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
