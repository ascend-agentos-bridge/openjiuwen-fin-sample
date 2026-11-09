import java.util.*;
/** 虚构业务服务：金额与状态由规则决定，模型不能绕过。 */
public final class CreditDomainService {
    private final Map<String,List<?>> plans=new HashMap<>();
    private final Set<String> supplemented=new HashSet<>();
    private final Map<String,String> states=new HashMap<>();
    private final Map<String,Map<String,Object>> quotes=new HashMap<>();
    public synchronized Map<String,Object> execute(String action,Map<String,Object> in){
        if(action.equals("query_product"))return Map.of("product","虚构经营贷","annual_rate",.038,"max_amount",1000000,"max_months",60,"note","仅演示规则，实际以银行审批为准");
        String id=String.valueOf(in.get("customer_id"));
        if(!Set.of("C1001","C1002","C1003","C1004").contains(id))throw new IllegalArgumentException("虚构客户仅有 C1001-C1004");
        String state=states.getOrDefault(id,"未申请");
        if(!Set.of("submit_plan","query_application","query_repayment","query_product").contains(action)&&!plans.containsKey(id))throw new IllegalStateException("请先提交并校验该客户计划");
        switch(action){
            case "submit_plan": if(!(in.get("steps") instanceof List<?> steps)||!steps.equals(List.of("受理","审查","审批","签约","放款","贷后")))throw new IllegalArgumentException("信贷计划必须保留受理、审查、审批、签约、放款、贷后的先后依赖");
            plans.putIfAbsent(id,steps);
            return Map.of("status","计划校验通过","steps",plans.get(id));
            case "check_documents": if(!state.equals("未申请"))return Map.of("status",state,"documents","资料齐全（既有受理记录）","missing",List.of());
            if(id.equals("C1003")&&!supplemented.contains(id))return Map.of("status","待补件","missing",List.of("经营流水","购销合同"));
            states.put(id,"已受理");
            return Map.of("status","资料齐全");
            case "check_risk": if(!Set.of("未申请","已受理").contains(state)){if(state.equals("拒绝"))return Map.of("status",state,"reason","反欺诈高风险或人工审批拒绝，终止办理");return Map.of("status",state,"review_status","审查已完成","overdue",0,"fraud_risk","LOW","monthly_income",60000,"existing_monthly_debt",5000);}
            if(id.equals("C1002")){
                states.put(id,"拒绝");
                return Map.of("status","拒绝","reason","反欺诈高风险，24月逾期3次");
            }
            if(!state.equals("已受理"))throw new IllegalStateException("请先受理资料");
            states.put(id,"审查通过");
            return Map.of("status","审查通过","overdue",0,"monthly_income",60000,"existing_monthly_debt",5000,"fraud_risk","LOW");
            case "quote_loan": if(quotes.containsKey(id)){
                var original=quotes.get(id);
                if(in.containsKey("amount")&&Double.compare(((Number)in.get("amount")).doubleValue(),((Number)original.get("amount")).doubleValue())!=0||in.containsKey("months")&&Double.compare(((Number)in.get("months")).doubleValue(),((Number)original.get("months")).doubleValue())!=0)throw new IllegalStateException("已有申请的金额或期限不可变更，请新建会话申请");
                var saved=new HashMap<String,Object>(quotes.get(id));
                saved.put("status",state);
                return saved;
            }
            if(!state.equals("审查通过"))throw new IllegalStateException("请先完成审查");
            double amount=((Number)in.get("amount")).doubleValue();
            double term=((Number)in.get("months")).doubleValue();
            if(!Double.isFinite(term)||term!=Math.rint(term)||amount!=Math.rint(amount))throw new IllegalArgumentException("金额与月份必须为整数");
            int months=(int)term;
            if(!Double.isFinite(amount)||amount<=0||amount>1000000||months<1||months>60)throw new IllegalArgumentException("演示额度1-1000000元，期限1-60月");
            double r=.038/12;
            states.put(id,amount>=500000?"待人工审批":"批准");
            Map<String,Object> quote=Map.of("status",states.get(id),"amount",amount,"annual_rate",.038,"months",months,"monthly_payment",Math.round(amount*r/(1-Math.pow(1+r,-months))*100)/100.0);
            quotes.put(id,quote);
            return quote;
            case "prepare_contract": if(!Set.of("批准","合同待签署","已签署并授权放款","已放款").contains(state))throw new IllegalStateException("未批准，不能准备合同");
            if(state.equals("批准"))states.put(id,"合同待签署");
            return Map.of("status",states.get(id),"note","需要客户在银行渠道完成签署；本演示不代签");
            case "disburse": if(state.equals("已放款"))return Map.of("status",state,"receipt","DEMO-"+id,"note","仅虚构账本，无真实资金交易");
            if(!state.equals("已签署并授权放款"))throw new IllegalStateException("请先在页面确认合同签署及放款授权");
            states.put(id,"已放款");
            return Map.of("status","已放款","receipt","DEMO-"+id,"note","仅虚构账本，无真实资金交易");
            case "query_repayment": if(state.equals("已放款")){
                var repayment=new HashMap<String,Object>(quotes.get(id));
                repayment.put("status",state);
                repayment.put("next_due","虚构放款次月15日");
                return repayment;
            }
            return Map.of("status",state,"note",state.equals("已放款")?"虚构首期还款计划已建立，无真实资金交易":"尚未放款，无还款计划");
            default:return Map.of("customer_id",id,"status",state,"data_source","虚构演示数据","plan",plans.getOrDefault(id,List.of()),"current_stage",state,"task_statuses",taskStatuses(id,state));
        }
    }
    public synchronized Map<String,Object> decision(String id,String action){
        String state=states.getOrDefault(id,"未申请");
        if(action.equals("supplement")){
            if(!id.equals("C1003")||!state.equals("未申请"))throw new IllegalStateException("当前没有待补件申请");
            supplemented.add(id);
            return Map.of("customer_id",id,"status","运行者已确认虚构经营流水和合同补齐");
        }
        if(action.equals("approve")||action.equals("reject")){
            if(!state.equals("待人工审批"))throw new IllegalStateException("该客户当前没有待审批申请");
            states.put(id,action.equals("approve")?"批准":"拒绝");
        }
        else if(action.equals("sign")){
            if(!state.equals("合同待签署"))throw new IllegalStateException("合同尚未准备");
            states.put(id,"已签署并授权放款");
        }
        else throw new IllegalArgumentException("未知操作");
        return Map.of("customer_id",id,"status",states.get(id),"source","运行者明确确认的虚构业务操作");
    }
    /** Read-only presentation snapshot; never advances a business operation. */
    public synchronized List<Map<String,Object>> snapshots() {
        List<Map<String,Object>> result=new ArrayList<>();
        for(String id:new TreeSet<>(plans.keySet())) {
            Map<String,Object> snapshot=new LinkedHashMap<>(execute("query_application",Map.of("customer_id",id)));
            if(quotes.containsKey(id))snapshot.put("quote",new LinkedHashMap<>(quotes.get(id)));
            if(id.equals("C1003")&&!supplemented.contains(id)&&!states.containsKey(id)){snapshot.put("status","待补件");snapshot.put("missing",List.of("经营流水","购销合同"));}
            if("已放款".equals(snapshot.get("status")))snapshot.put("receipt","DEMO-"+id);
            result.add(snapshot);
        }
        return result;
    }
    private List<Map<String,String>> taskStatuses(String id,String state){
        List<?> plan=plans.getOrDefault(id,List.of());
        int completed=switch(state){
            case "已受理"->1;
            case "审查通过","待人工审批","拒绝"->2;
            case "批准","合同待签署"->3;
            case "已签署并授权放款"->4;
            case "已放款"->5;
            default->0;
        }
        ;
        List<Map<String,String>> tasks=new ArrayList<>();
        for(int i=0;i<plan.size();i++)tasks.add(Map.of("step",String.valueOf(plan.get(i)),"state",state.equals("拒绝")?(i<2?"completed":i==2?"rejected":"cancelled"):i<completed?"completed":i==completed?"pending":"blocked"));
        return tasks;
    }
}
