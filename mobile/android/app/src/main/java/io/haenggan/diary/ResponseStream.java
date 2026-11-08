package io.haenggan.diary;

import java.io.*;
import org.json.*;

final class ResponseStream {
    static String read(BufferedReader reader) throws Exception {
        String line; StringBuilder frame = new StringBuilder(), text = new StringBuilder(); boolean completed = false;
        while ((line=reader.readLine())!=null) {
            if (line.startsWith("data:")) { if(frame.length()>0)frame.append('\n'); frame.append(line.substring(5).stripLeading()); }
            else if(line.isEmpty() && frame.length()>0) {
                String raw=frame.toString(); frame.setLength(0); if(raw.equals("[DONE]")) continue;
                JSONObject e=new JSONObject(raw); String type=e.optString("type");
                if(type.equals("response.output_text.delta")) text.append(e.optString("delta"));
                if(text.length()>100000 || raw.length()>1000000)throw new IOException("응답이 너무 길어요.");
                if(type.equals("response.failed") || type.equals("error") || type.equals("response.incomplete")) {
                    JSONObject error=e.optJSONObject("error");
                    if(error==null && e.optJSONObject("response")!=null)error=e.getJSONObject("response").optJSONObject("error");
                    String code=error==null?e.optString("code"):error.optString("code");
                    if(code.contains("usage_limit") || code.contains("usage_unavailable")) throw new IOException("ChatGPT 구독의 사용 한도에 도달했어요. 설정에서 사용량을 확인해 주세요.");
                    throw new IOException("정리를 마치지 못했어요. 원문은 그대로 보관돼요.");
                }
                if(type.equals("response.completed")) {
                    JSONObject r=e.optJSONObject("response");
                    if(r==null || !"completed".equals(r.optString("status")))throw new IOException("정리가 완료되지 않았어요.");
                    completed=true;
                    if(text.length()==0) {
                        JSONArray output=r.optJSONArray("output");
                        if(output!=null)for(int i=0;i<output.length();i++) {
                            JSONArray content=output.getJSONObject(i).optJSONArray("content");
                            if(content!=null)for(int j=0;j<content.length();j++)if("output_text".equals(content.getJSONObject(j).optString("type")))text.append(content.getJSONObject(j).optString("text"));
                        }
                    }
                    break;
                }
            }
            if(frame.length()>1000000)throw new IOException("응답이 너무 길어요.");
        }
        if(!completed || text.toString().isBlank())throw new IOException("연결이 끊겨 정리를 마치지 못했어요. 원문은 보관돼요.");
        return text.toString();
    }
}
