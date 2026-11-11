import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.*;
/** Trusted business facts rendered by the official A2UI basic catalog. */
public final class CreditSurfaces {
    private static final java.util.Locale FORMAT_LOCALE=java.util.Locale.US;
    private static Map<String,Object> fact(String label,String value){return Map.of("label",label,"value",value);}
    private static String money(double value,String pattern){
        DecimalFormat format=new DecimalFormat(pattern,DecimalFormatSymbols.getInstance(FORMAT_LOCALE));
        return "¥"+format.format(value);
    }
    private static String percent(double rate){
        return BigDecimal.valueOf(rate).multiply(BigDecimal.valueOf(100)).setScale(2,RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()+"%";
    }
    private static String tone(String status){
        return switch(status){
            case "批准","合同待签署","已签署并授权放款","已放款"->"ok";
            case "待人工审批","待补件"->"warn";
            case "拒绝"->"danger";
            default->"info";
        };
    }
    private static String nextStep(String status){
        return switch(status){
            case "待人工审批"->"等待审批人员确认（演示角色），批准后可继续准备合同";
            case "批准"->"可以准备合同";
            case "合同待签署"->"等待客户签署及放款授权";
            case "已签署并授权放款"->"可以继续办理放款";
            case "已放款"->"可查询还款安排";
            case "拒绝"->"申请已被拒绝";
            case "待补件"->"请补充经营流水与购销合同";
            default->null;
        };
    }
    /** Customer-facing structured reply; read-only view of the business snapshots. */
    static Map<String,Object> reply(CreditDomainService domain) {
        List<Object> cases=new ArrayList<>();
        for(Map<String,Object> snapshot:domain.snapshots()) {
            String customer=String.valueOf(snapshot.get("customer_id"));
            String status=String.valueOf(snapshot.get("status"));
            Map<String,Object> item=new LinkedHashMap<>();
            item.put("customer_id",customer);
            item.put("status",status);
            item.put("tone",tone(status));
            List<Object> facts=new ArrayList<>();
            if(snapshot.get("quote") instanceof Map<?,?> quote){
                facts.add(fact("申请金额",money(((Number)quote.get("amount")).doubleValue(),"#,##0.##")));
                facts.add(fact("还款期限",quote.get("months")+" 期"));
                facts.add(fact("年化利率",percent(((Number)quote.get("annual_rate")).doubleValue())));
                facts.add(fact("每月还款",money(((Number)quote.get("monthly_payment")).doubleValue(),"#,##0.00")));
            }
            item.put("facts",facts);
            String nextStep=nextStep(status);
            if(nextStep!=null)item.put("next_step",nextStep);
            cases.add(item);
        }
        return Map.of("cases",cases);
    }
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
                metric(components,"amount","申请金额",String.format("¥%,.0f",((Number)quote.get("amount")).doubleValue()));
                metric(components,"term","还款期限",quote.get("months")+" 期");
                metric(components,"rate","年化利率",String.format("%.1f%%",((Number)quote.get("annual_rate")).doubleValue()*100));
                metric(components,"monthly","每月还款",String.format("¥%,.2f",((Number)quote.get("monthly_payment")).doubleValue()));
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
