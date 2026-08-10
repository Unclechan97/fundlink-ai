package com.fundlink.ai.agent.requirement;

import java.util.ArrayList;
import java.util.List;

/**
 * 多接口并行处理结果聚合。
 */
public class MultiInterfaceResult {

    private String providerCode;
    private int totalCount;
    private int successCount;
    private int failedCount;
    private List<InterfaceResultItem> interfaces = new ArrayList<>();

    // ── Getters / Setters ──

    public String getProviderCode() { return providerCode; }
    public void setProviderCode(String s) { this.providerCode = s; }

    public int getTotalCount() { return totalCount; }
    public void setTotalCount(int n) { this.totalCount = n; }

    public int getSuccessCount() { return successCount; }
    public void setSuccessCount(int n) { this.successCount = n; }

    public int getFailedCount() { return failedCount; }
    public void setFailedCount(int n) { this.failedCount = n; }

    public List<InterfaceResultItem> getInterfaces() { return interfaces; }
    public void setInterfaces(List<InterfaceResultItem> list) { this.interfaces = list; }

    // ── Item ──

    public static class InterfaceResultItem {
        private String interfaceId;
        private String interfaceName;
        private String endpoint;
        private String status; // SUCCESS / FAILED / TIMEOUT
        private String errorMessage;
        private RequirementResult result;

        public static InterfaceResultItem success(String interfaceId, String interfaceName,
                                                  String endpoint, RequirementResult result) {
            InterfaceResultItem item = new InterfaceResultItem();
            item.interfaceId = interfaceId;
            item.interfaceName = interfaceName;
            item.endpoint = endpoint;
            item.status = "SUCCESS";
            item.result = result;
            return item;
        }

        public static InterfaceResultItem failed(String interfaceId, String interfaceName,
                                                 String endpoint, String errorMessage) {
            InterfaceResultItem item = new InterfaceResultItem();
            item.interfaceId = interfaceId;
            item.interfaceName = interfaceName;
            item.endpoint = endpoint;
            item.status = "FAILED";
            item.errorMessage = errorMessage;
            return item;
        }

        public static InterfaceResultItem timeout(String interfaceId, String interfaceName,
                                                  String endpoint) {
            InterfaceResultItem item = new InterfaceResultItem();
            item.interfaceId = interfaceId;
            item.interfaceName = interfaceName;
            item.endpoint = endpoint;
            item.status = "TIMEOUT";
            return item;
        }

        // ── Getters ──

        public String getInterfaceId() { return interfaceId; }
        public String getInterfaceName() { return interfaceName; }
        public String getEndpoint() { return endpoint; }
        public String getStatus() { return status; }
        public String getErrorMessage() { return errorMessage; }
        public RequirementResult getResult() { return result; }
    }
}
