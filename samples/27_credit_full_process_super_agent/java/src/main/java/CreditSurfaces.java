import java.util.*;
/** Trusted business facts rendered by the official A2UI basic catalog. */
public final class CreditSurfaces {
    static Map<String,Object> text(String id,String value){return Map.of("id",id,"component","Text","text",value);}
    private static void metric(List<Object> components,String id,String label,String value){
        components.add(Map.of("id",id+"Label","component","Text","text",label,"variant","caption"));
        components.add(Map.of("id",id+"Value","component","Text","text",value,"variant","h3"));
        components.add(Map.of("id",id,"component","Column","children",List.of(id+"Label",id+"Value")));
    }
    private static String stateLabel(String state){return switch(state){case "completed"->"已完成";case "pending"->"待处理";case "blocked"->"待前序完成";case "rejected"->"已拒绝";case "cancelled"->"已终止";default->state;};}
    static Map<String,Object> event(CreditDomainService domain) {
        List<Object> messages=new ArrayList<>();
        for(Map<String,Object> snapshot:domain.snapshots()) {
            String customer=String.valueOf(snapshot.get("customer_id")), status=String.valueOf(snapshot.get("status"));
            String surface="credit-"+UUID.randomUUID();
            List<Object> components=new ArrayList<>(); List<String> children=new ArrayList<>();
            components.add(Map.of("id","title","component","Text","text",customer+" · "+status,"variant","h3"));children.add("title");
            if(snapshot.get("quote") instanceof Map<?,?> quote){
                metric(components,"amount","申请金额","¥"+quote.get("amount"));
                metric(components,"term","还款期限",quote.get("months")+" 期");
                metric(components,"rate","年化利率",(((Number)quote.get("annual_rate")).doubleValue()*100)+"%");
                metric(components,"monthly","每月还款","¥"+quote.get("monthly_payment"));
                components.add(Map.of("id","metrics1","component","Row","children",List.of("amount","term"),"justify","spaceBetween"));children.add("metrics1");
                components.add(Map.of("id","metrics2","component","Row","children",List.of("rate","monthly"),"justify","spaceBetween"));children.add("metrics2");
            }
            if(snapshot.get("task_statuses") instanceof List<?> tasks)for(int i=0;i<tasks.size();i++){
                Map<?,?> task=(Map<?,?>)tasks.get(i);String id="step"+i;components.add(text(id+"Name",String.valueOf(task.get("step"))));components.add(Map.of("id",id+"Status","component","Text","text",stateLabel(String.valueOf(task.get("state"))),"variant","caption"));components.add(Map.of("id",id,"component","Row","children",List.of(id+"Name",id+"Status"),"justify","spaceBetween"));children.add(id);
            }
            List<String> actions=switch(status){case "待人工审批"->List.of("approve","reject");case "合同待签署"->List.of("sign");case "待补件"->List.of("supplement");default->List.of();};
            for(String action:actions){String label=switch(action){case "approve"->"审批批准";case "reject"->"审批拒绝";case "sign"->"确认签署及放款授权";default->"确认演示材料已补齐";};components.add(text(action+"Label",label));components.add(Map.of("id",action,"component","Button","child",action+"Label","variant","primary","action",Map.of("event",Map.of("name",action,"context",Map.of("customer_id",customer)))));children.add(action);}
            if(snapshot.containsKey("missing")){components.add(text("missing","待补充："+snapshot.get("missing")));children.add("missing");}
            if(snapshot.containsKey("receipt")){components.add(text("receipt","演示放款回执："+snapshot.get("receipt")));children.add("receipt");}
            components.add(Map.of("id","body","component","Column","children",children));
            components.add(Map.of("id","root","component","Card","child","body"));
            messages.add(Map.of("version","v0.9","createSurface",Map.of("surfaceId",surface,"catalogId","https://a2ui.org/specification/v0_9/catalogs/basic/catalog.json")));
            messages.add(Map.of("version","v0.9","updateComponents",Map.of("surfaceId",surface,"components",components)));
        }
        return Map.of("id","a2ui","messages",messages);
    }
}
