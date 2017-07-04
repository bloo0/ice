/*
 *
 *  Copyright 2013 Netflix, Inc.
 *
 *     Licensed under the Apache License, Version 2.0 (the "License");
 *     you may not use this file except in compliance with the License.
 *     You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *     Unless required by applicable law or agreed to in writing, software
 *     distributed under the License is distributed on an "AS IS" BASIS,
 *     WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *     See the License for the specific language governing permissions and
 *     limitations under the License.
 *
 */
package com.netflix.ice.basic;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.netflix.ice.common.*;
import com.netflix.ice.processor.*;
import com.netflix.ice.processor.Ec2InstanceReservationPrice.ReservationUtilization;
import com.netflix.ice.tag.*;

import org.apache.commons.lang.StringUtils;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;


/*
 * All reservation usage starts out tagged as BonusReservedInstances and is later reassigned proper tags
 * based on it's usage by the ReservationProcessor.
 */
public class BasicLineItemProcessor implements LineItemProcessor {
    private Logger logger = LoggerFactory.getLogger(BasicLineItemProcessor.class);

    private int accountIdIndex;
    private int productIndex;
    private int zoneIndex;
    private int reservedIndex;
    private int descriptionIndex;
    private int usageTypeIndex;
    private int operationIndex;
    private int usageQuantityIndex;
    private int startTimeIndex;
    private int endTimeIndex;
    //private int rateIndex;
    private int costIndex;
    private int resourceIndex;

    private List<String> header;
    
    private AccountService accountService;
    private ProductService productService;
    private ReservationService reservationService;
    private boolean modeledCostForResourceGroup;
    private double costPerMonitorMetricPerHour;

    private ResourceService resourceService;
    private Randomizer randomizer;
    
    public BasicLineItemProcessor(
    		AccountService accountService, 
    		ProductService productService, 
    		ReservationService reservationService, 
    		ResourceService resourceService, 
    		Randomizer randomizer) {
    	this.accountService = accountService;
    	this.productService = productService;
    	this.reservationService = reservationService;
    	this.resourceService = resourceService;
    	this.randomizer = randomizer;
    }
    
    public void init(boolean modeledCostForResourceGroup, double costPerMonitorMetricPerHour) {
    	this.modeledCostForResourceGroup = modeledCostForResourceGroup;
    	this.costPerMonitorMetricPerHour = costPerMonitorMetricPerHour;
    }

    public void initIndexes(boolean useBlended, boolean withTags, String[] header) {
        boolean hasBlendedCost = false;
        boolean useBlendedCost = useBlended;
        for (String column: header) {
            if (column.equalsIgnoreCase("UnBlendedCost")) {
                hasBlendedCost = true;
                break;
            }
        }
        accountIdIndex = 2;
        productIndex = 5 + (withTags ? 0 : -1);
        zoneIndex = 11 + (withTags ? 0 : -1);
        reservedIndex = 12 + (withTags ? 0 : -1);
        descriptionIndex = 13 + (withTags ? 0 : -1);
        usageTypeIndex = 9 + (withTags ? 0 : -1);
        operationIndex = 10 + (withTags ? 0 : -1);
        usageQuantityIndex = 16 + (withTags ? 0 : -1);
        startTimeIndex = 14 + (withTags ? 0 : -1);
        endTimeIndex = 15 + (withTags ? 0 : -1);
        // When blended vales are present, the rows look like this
        //    ..., UsageQuantity, BlendedRate, BlendedCost, UnBlended Rate, UnBlended Cost
        // Without Blended Rates
        //    ..., UsageQuantity, UnBlendedRate, UnBlendedCost
        // We want to always reference the UnBlended Cost unless useBlendedCost is true.
        //rateIndex = 19 + (withTags ? 0 : -1) + ((hasBlendedCost && useBlendedCost == false) ? 0 : -2);
        costIndex = 20 + (withTags ? 0 : -1) + ((hasBlendedCost && useBlendedCost == false) ? 0 : -2);
        resourceIndex = 21 + (withTags ? 0 : -1) + (hasBlendedCost ? 0 : -2);

        this.header = Lists.newArrayList(header);
        
        if (resourceService != null) {
        	resourceService.initHeader(this.header);
        }
    }

    public List<String> getHeader() {
        return this.header;
    }

    public int getUserTagStartIndex() {
        return resourceIndex + 1;
    }

    public long getEndMillis(String[] items) {
        return amazonBillingDateFormat.parseMillis(items[endTimeIndex]);
    }

    public Result process(
    		long startMilli, 
    		boolean processDelayed, 
    		String[] items, 
    		Map<Product, ReadWriteData> usageDataByProduct, 
    		Map<Product, ReadWriteData> costDataByProduct, 
    		Map<String, Double> ondemandRate, 
    		Instances instances) {
    	
        if (StringUtils.isEmpty(items[accountIdIndex]) ||
            StringUtils.isEmpty(items[productIndex]) ||
            StringUtils.isEmpty(items[costIndex]))
            return Result.ignore;

        Account account = accountService.getAccountById(items[accountIdIndex]);
        if (account == null)
            return Result.ignore;

        Product product = productService.getProductByAwsName(items[productIndex]);
        double usageValue = 0.0;
        if (!product.isSupport()) {
        	if (StringUtils.isEmpty(items[usageTypeIndex]) ||
                StringUtils.isEmpty(items[operationIndex]) ||
                StringUtils.isEmpty(items[usageQuantityIndex])) {
        		return Result.ignore;
        	}
            usageValue = Double.parseDouble(items[usageQuantityIndex]);
        }

        double costValue = Double.parseDouble(items[costIndex]);

        long millisStart;
        long millisEnd;
        try {
            millisStart = amazonBillingDateFormat.parseMillis(items[startTimeIndex]);
            millisEnd = amazonBillingDateFormat.parseMillis(items[endTimeIndex]);
        }
        catch (IllegalArgumentException e) {
            millisStart = amazonBillingDateFormat2.parseMillis(items[startTimeIndex]);
            millisEnd = amazonBillingDateFormat2.parseMillis(items[endTimeIndex]);
        }

        boolean reservationUsage = "Y".equals(items[reservedIndex]);
        ReformedMetaData reformedMetaData = reform(reservationService.getDefaultReservationUtilization(millisStart), 
        		product, reservationUsage, items[operationIndex], items[usageTypeIndex], items[descriptionIndex], costValue);
        product = reformedMetaData.product;
        Operation operation = reformedMetaData.operation;
        final UsageType usageType = reformedMetaData.usageType;
        Zone zone = Zone.getZone(items[zoneIndex], reformedMetaData.region);

        int startIndex = (int)((millisStart - startMilli)/ AwsUtils.hourMillis);
        int endIndex = (int)((millisEnd + 1000 - startMilli)/ AwsUtils.hourMillis);

        Result result = Result.hourly;
        if (instances != null && product.isEc2Instance()) {
            result = processEc2Instance(processDelayed, reservationUsage, operation, zone);
            if (items.length > resourceIndex)
            	instances.add(items[resourceIndex], usageType.toString(), getUserTagsString(items));
        }
        else if (product.isRedshift()) {
            result = processRedshift(processDelayed, reservationUsage, operation, costValue);
            //logger.info("Process Redshift " + operation + " " + costValue + " returned: " + result);
        }
        else if (product.isDataTransfer()) {
            result = processDataTranfer(processDelayed, usageType);
        }
        else if (product.isCloudHsm()) {
            result = processCloudhsm(processDelayed, usageType);
        }
        else if (product.isEbs()) {
            result = processEbs(usageType);
        }
        else if (product.isRds() || product.isRdsInstance()) {
            result = processRds(usageType, processDelayed, reservationUsage, operation, costValue);
//            if (startIndex == 0 && reservationUsage) {
//            	logger.info(" ----- RDS usage=" + usageType + ", delayed=" + processDelayed + ", operation=" + operation + ", cost=" + costValue + ", result=" + result);
//            }
        }
        else if (product.isSupport()) {
        	result = Result.monthly;
        	//logger.info("Support lineitem: " + costValue);
        }

        if (result == Result.ignore || result == Result.delay)
            return result;

        if (usageType.name.startsWith("TimedStorage-ByteHrs"))
            result = Result.daily;

        boolean monthlyCost = StringUtils.isEmpty(items[descriptionIndex]) ? false : items[descriptionIndex].toLowerCase().contains("-month");

        ReadWriteData usageData = usageDataByProduct.get(null);
        ReadWriteData costData = costDataByProduct.get(null);
        ReadWriteData usageDataOfProduct = usageDataByProduct.get(product);
        ReadWriteData costDataOfProduct = costDataByProduct.get(product);

        if (result == Result.daily) {
            millisStart = new DateTime(millisStart).withTimeAtStartOfDay().getMillis();
            startIndex = (int)((millisStart - startMilli)/ AwsUtils.hourMillis);
            endIndex = startIndex + 24;
        }
        else if (result == Result.monthly) {
            startIndex = 0;
            endIndex = usageData.getNum();
            int numHoursInMonth = new DateTime(startMilli, DateTimeZone.UTC).dayOfMonth().getMaximumValue() * 24;
            usageValue = usageValue * endIndex / numHoursInMonth;
            costValue = costValue * endIndex / numHoursInMonth;
        }

        if (monthlyCost) {
            int numHoursInMonth = new DateTime(startMilli, DateTimeZone.UTC).dayOfMonth().getMaximumValue() * 24;
            usageValue = usageValue * numHoursInMonth;
        }

        int[] indexes;
        if (endIndex - startIndex > 1) {
            usageValue = usageValue / (endIndex - startIndex);
            costValue = costValue / (endIndex - startIndex);
            indexes = new int[endIndex - startIndex];
            for (int i = 0; i < indexes.length; i++)
                indexes[i] = startIndex + i;
        }
        else {
            indexes = new int[]{startIndex};
        }

        TagGroup tagGroup = TagGroup.getTagGroup(account, reformedMetaData.region, zone, product, operation, usageType, null);
        TagGroup resourceTagGroup = null;

        if (costValue > 0 && !reservationUsage && product.isEc2Instance() && tagGroup.operation == Operation.ondemandInstances) {
            String key = operation + "|" + tagGroup.region + "|" + usageType;
            ondemandRate.put(key, costValue/usageValue);
        }

        double resourceCostValue = costValue;
        if (items.length > resourceIndex && !StringUtils.isEmpty(items[resourceIndex]) && resourceService != null) {
        	
            if (modeledCostForResourceGroup && product.isEc2Instance())
                operation = Operation.getBonusReservedInstances(reservationService.getDefaultReservationUtilization(0L));

            if (product.isEc2Instance() && operation instanceof Operation.ReservationOperation &&
            		operation != Operation.ondemandInstances && operation != Operation.spotInstances) {
                UsageType usageTypeForPrice = usageType;
                if (usageType.name.endsWith(InstanceOs.others.name())) {
                    usageTypeForPrice = UsageType.getUsageType(usageType.name.replace(InstanceOs.others.name(), InstanceOs.windows.name()), usageType.unit);
                }
                try {
                    resourceCostValue = usageValue * reservationService.getLatestHourlyTotalPrice(millisStart, tagGroup.region, usageTypeForPrice, reservationService.getDefaultReservationUtilization(0L));
                }
                catch (Exception e) {
                    logger.error("failed to get RI price for " + tagGroup.region + " " + usageTypeForPrice + " " + operation);
                    resourceCostValue = -1;
                }
            }

            String resourceGroupStr = resourceService.getResource(account, reformedMetaData.region, product, items[resourceIndex], items, millisStart);
            if (!StringUtils.isEmpty(resourceGroupStr)) {
                ResourceGroup resourceGroup = ResourceGroup.getResourceGroup(resourceGroupStr);
                resourceTagGroup = TagGroup.getTagGroup(account, reformedMetaData.region, zone, product, operation, usageType, resourceGroup);
                if (usageDataOfProduct == null) {
                    usageDataOfProduct = new ReadWriteData();
                    costDataOfProduct = new ReadWriteData();
                    usageDataByProduct.put(product, usageDataOfProduct);
                    costDataByProduct.put(product, costDataOfProduct);
                }
            }
        }

        // Does the following code need to check for Product.cloudwatch in addition to Product.monitor?
        // I have a suspicion that Amazon renamed the service. -jroth
        if (randomizer != null && product.isMonitor())
            return result;

        for (int i : indexes) {

            if (randomizer != null) {

                if (!tagGroup.product.isRds() && !tagGroup.product.isS3() && usageData.getData(i).get(tagGroup) != null)
                    break;

                long time = millisStart + i * AwsUtils.hourMillis;
                usageValue = randomizer.randomizeUsage(time, resourceTagGroup == null ? tagGroup : resourceTagGroup, usageValue);
                costValue = usageValue * randomizer.randomizeCost(tagGroup);
            }
            if (!product.isMonitor()) {
                Map<TagGroup, Double> usages = usageData.getData(i);
                Map<TagGroup, Double> costs = costData.getData(i);
                
                // Redshift and RDS have cost as a monthly charge, but usage appears hourly
                // so unlike EC2, we have to process the monthly line item to capture the cost,
                // but we don't want to add the monthly line items to the usage.
                if (!((product.isRedshift() || product.isRdsInstance()) && result == Result.monthly)) {
                	addValue(usages, tagGroup, usageValue,  randomizer == null || tagGroup.product.isRds() || tagGroup.product.isS3());
                }

                addValue(costs, tagGroup, costValue, randomizer == null || tagGroup.product.isRds() || tagGroup.product.isS3());
            }
            else {
                resourceCostValue = usageValue * costPerMonitorMetricPerHour;
            }

            if (resourceTagGroup != null) {
                Map<TagGroup, Double> usagesOfResource = usageDataOfProduct.getData(i);
                Map<TagGroup, Double> costsOfResource = costDataOfProduct.getData(i);

                if (randomizer == null || tagGroup.product.isRds() || tagGroup.product.isS3()) {
                    if (!((product.isRedshift() || product.isRds()) && result == Result.monthly)) {
                    	addValue(usagesOfResource, resourceTagGroup, usageValue, !product.isMonitor());
                    }
                    
                    if (!modeledCostForResourceGroup || resourceCostValue < 0) {
                        addValue(costsOfResource, resourceTagGroup, costValue, !product.isMonitor());
                    } else {
                        addValue(costsOfResource, resourceTagGroup, resourceCostValue, !product.isMonitor());
                    }
                }
                else {
                    Map<String, Double> distribution = randomizer.getDistribution(tagGroup);
                    for (Map.Entry<String, Double> entry : distribution.entrySet()) {
                        String app = entry.getKey();
                        double dist = entry.getValue();
                        resourceTagGroup = TagGroup.getTagGroup(account, reformedMetaData.region, zone, product, operation, usageType, ResourceGroup.getResourceGroup(app));
                        double usage = usageValue * dist;
                        if (product.isEc2Instance())
                            usage = (int)usageValue * dist;
                        addValue(usagesOfResource, resourceTagGroup, usage, false);
                        addValue(costsOfResource, resourceTagGroup, usage * randomizer.randomizeCost(tagGroup), false);
                    }
                }
            }
        }

        return result;
    }

    private void addValue(Map<TagGroup, Double> map, TagGroup tagGroup, double value, boolean add) {
        Double oldV = map.get(tagGroup);
        if (oldV != null) {
            value = add ? value + oldV : value;
        }

        map.put(tagGroup, value);
    }

    private Result processEc2Instance(boolean processDelayed, boolean reservationUsage, Operation operation, Zone zone) {
        if (!processDelayed && zone == null && operation.isBonus() && reservationUsage)
            return Result.ignore;
        else
            return Result.hourly;
    }

    private Result processRedshift(boolean processDelayed, boolean reservationUsage, Operation operation, double costValue) {
        if (!processDelayed && costValue != 0 && operation.isBonus() && reservationUsage)
            return Result.delay;
        else if (!processDelayed && costValue == 0 && operation.isBonus() && reservationUsage)
            return Result.hourly;
        else if (processDelayed && costValue != 0 && operation.isBonus() && reservationUsage)
            return Result.monthly;
        else
            return Result.hourly;
    }

    private Result processDataTranfer(boolean processDelayed, UsageType usageType) {
        if (!processDelayed && usageType.name.contains("PrevMon-DataXfer-"))
            return Result.delay;
        else if (processDelayed && usageType.name.contains("PrevMon-DataXfer-"))
            return Result.monthly;
        else
            return Result.hourly;
    }

    private Result processCloudhsm(boolean processDelayed, UsageType usageType) {
        if (!processDelayed && usageType.name.contains("CloudHSMUpfront"))
            return Result.delay;
        else if (processDelayed && usageType.name.contains("CloudHSMUpfront"))
            return Result.monthly;
        else
            return Result.hourly;
    }

    private Result processEbs(UsageType usageType) {
        if (usageType.name.startsWith("EBS:SnapshotUsage"))
            return Result.daily;
        else
            return Result.hourly;
    }

    private Result processRds(UsageType usageType, boolean processDelayed, boolean reservationUsage, Operation operation, double costValue) {
        if (usageType.name.startsWith("RDS:ChargedBackupUsage"))
            return Result.daily;
        else if (!processDelayed && costValue != 0 && operation.isBonus() && reservationUsage)
            return Result.delay; // Must be a monthly charge for all the hourly usage
        else if (!processDelayed && costValue == 0 && operation.isBonus() && reservationUsage)
            return Result.hourly; // Must be the hourly usage
        else if (processDelayed && costValue != 0 && operation.isBonus() && reservationUsage)
            return Result.monthly; // This is the post processing of the monthly charge for all the hourly usage
        else
            return Result.hourly;
    }

    protected ReformedMetaData reform(
    		ReservationUtilization defaultReservationUtilization,
    		Product product, 
    		boolean reservationUsage, 
    		String operationStr, 
    		String usageTypeStr, 
    		String description, 
    		double cost) {

        Operation operation = null;
        UsageType usageType = null;
        InstanceOs os = null;
        InstanceDb db = null;

        // first try to retrieve region info
        int index = usageTypeStr.indexOf("-");
        String regionShortName = index > 0 ? usageTypeStr.substring(0, index) : "";
        Region region = regionShortName.isEmpty() ? null : Region.getRegionByShortName(regionShortName);
        if (region != null) {
            usageTypeStr = usageTypeStr.substring(index+1);
        }
        else {
            region = Region.US_EAST_1;
        }

        if (operationStr.equals("EBS Snapshot Copy")) {
            product = productService.getProductByName(Product.ebs);
        }

        if (usageTypeStr.startsWith("ElasticIP:")) {
            product = productService.getProductByName(Product.eip);
        }
        else if (usageTypeStr.startsWith("EBS:"))
            product = productService.getProductByName(Product.ebs);
        else if (usageTypeStr.startsWith("EBSOptimized:"))
            product = productService.getProductByName(Product.ebs);
        else if (usageTypeStr.startsWith("CW:"))
            product = productService.getProductByName(Product.ec2CloudWatch);
        else if ((usageTypeStr.startsWith("BoxUsage") || usageTypeStr.startsWith("SpotUsage")) && operationStr.startsWith("RunInstances")) {
        	// Line item for hourly "All Upfront", "Spot", or "On-Demand" EC2 instance usage
        	boolean spot = usageTypeStr.startsWith("SpotUsage");
            index = usageTypeStr.indexOf(":");
            usageTypeStr = index < 0 ? "m1.small" : usageTypeStr.substring(index+1);

            if (reservationUsage && product.isEc2()) {
            	if (cost == 0)
                    operation = Operation.bonusReservedInstancesFixed;
            	else
                    operation = Operation.getBonusReservedInstances(defaultReservationUtilization);
            }
            else if (spot)
            	operation = Operation.spotInstances;
            else
                operation = Operation.ondemandInstances;
            os = getInstanceOs(operationStr);
        }
        else if (usageTypeStr.startsWith("Node") && operationStr.startsWith("RunComputeNode")) {
        	// Line item for hourly Redshift instance usage both On-Demand and Reserved.
            usageTypeStr = currentRedshiftUsageType(usageTypeStr.split(":")[1]);
            
        	// Fixed, Heavy, and HeavyPartial apply cost monthly,
        	// so can't tell them apart without looking at the description. Examples:
        	// No Upfront:  "Redshift, dw2.8xlarge instance-hours used this month"
        	// All Upfront: "USD 0.0 per Redshift, dw2.8xlarge instance-hour (or partial hour)"
            if (reservationUsage && product.isRedshift() && cost == 0 && description.contains(" 0.0 per"))
            	operation = Operation.bonusReservedInstancesFixed;
            else if (reservationUsage && product.isRedshift())
                operation = Operation.getBonusReservedInstances(defaultReservationUtilization);
            else
            	operation = Operation.ondemandInstances;
            os = getInstanceOs(operationStr);
        }
        else if ((usageTypeStr.startsWith("InstanceUsage") || usageTypeStr.startsWith("Multi-AZUsage")) && operationStr.startsWith("CreateDBInstance")) {
        	// Line item for hourly RDS instance usage - both On-Demand and Reserved
            usageTypeStr = usageTypeStr.split(":")[1] + (usageTypeStr.startsWith("Multi") ? ".multiaz" : "");
            
        	// Fixed, Heavy, and HeavyPartial apply cost monthly,
        	// so can't tell them apart without looking at the description. Examples:
            // --- Need examples, for now assuming it's the same as for Redshift ---
        	// No Upfront:  "Redshift, dw2.8xlarge instance-hours used this month"
        	// All Upfront: "USD 0.0 per Redshift, dw2.8xlarge instance-hour (or partial hour)"
            if (reservationUsage && product.isRds() && cost == 0 && description.contains(" 0.0 per"))
            	operation = Operation.bonusReservedInstancesFixed;
            else if (reservationUsage && product.isRds())
                operation = Operation.getBonusReservedInstances(defaultReservationUtilization);
            else
            	operation = Operation.ondemandInstances;
            db = getInstanceDb(operationStr);
        }
        else if (usageTypeStr.startsWith("HeavyUsage")) {
        	// Line item for hourly "No Upfront" or "Partial Upfront" EC2 or monthly "No Upfront" or "Partial Upfront" for Redshift and RDS)
            index = usageTypeStr.indexOf(":");
            if (index < 0) {
                usageTypeStr = "m1.small";
            }
            else {
                usageTypeStr = usageTypeStr.substring(index+1);
                if (product.isRedshift()) {
                	usageTypeStr = currentRedshiftUsageType(usageTypeStr);
                }
            }
            if (product.isRds()){
                db = getInstanceDb(operationStr);
            }
            operation = getOperation(operationStr, reservationUsage, defaultReservationUtilization);
            os = getInstanceOs(operationStr);
        }

        // Usage type string is empty for Support recurring fees.
        if (usageTypeStr.equals("Unknown") || usageTypeStr.equals("Not Applicable") || usageTypeStr.isEmpty()) {
            usageTypeStr = product.name;
        }

        if (operation == null) {
            operation = Operation.getOperation(operationStr);
        }

        if (product.isEc2() && operation instanceof Operation.ReservationOperation) {
            product = productService.getProductByName(Product.ec2Instance);
            if (os != InstanceOs.linux && os != InstanceOs.spot) {
                usageTypeStr = usageTypeStr + "." + os;
                operation = operation.isBonus() ? operation : Operation.ondemandInstances;
            }
        }

        if (product.isRds() && operation instanceof Operation.ReservationOperation) {
            product = productService.getProductByName(Product.rdsInstance);
            usageTypeStr = usageTypeStr + "." + db;
            operation = operation.isBonus() ? operation : Operation.ondemandInstances;
        }

        if (usageType == null) {
            usageType = UsageType.getUsageType(usageTypeStr, operation, description);
        }

        return new ReformedMetaData(region, product, operation, usageType);
    }

    private InstanceOs getInstanceOs(String operationStr) {
        int index = operationStr.indexOf(":");
        String osStr = index > 0 ? operationStr.substring(index) : "";
        return InstanceOs.withCode(osStr);
    }

    private InstanceDb getInstanceDb(String operationStr) {
        int index = operationStr.indexOf(":");
        String osStr = index > 0 ? operationStr.substring(index) : "";
        return InstanceDb.withCode(osStr);
    }

    private Operation getOperation(String operationStr, boolean reservationUsage, Ec2InstanceReservationPrice.ReservationUtilization utilization) {
        if (operationStr.startsWith("RunInstances") ||
        		operationStr.startsWith("RunComputeNode") ||
        		operationStr.startsWith("CreateDBInstance")) {
            return (reservationUsage ? Operation.getBonusReservedInstances(utilization) : Operation.ondemandInstances);
        }
        return null;
    }
    
    private static final Map<String, String> redshiftUsageTypeMap = Maps.newHashMap();
    static {
    	redshiftUsageTypeMap.put("dw.hs1.xlarge", "ds1.xlarge");
    	redshiftUsageTypeMap.put("dw1.xlarge", "ds1.xlarge");
    	redshiftUsageTypeMap.put("dw.hs1.8xlarge", "ds1.8xlarge");
    	redshiftUsageTypeMap.put("dw1.8xlarge", "ds1.8xlarge");
    	redshiftUsageTypeMap.put("dw2.large", "dc1.large");
    	redshiftUsageTypeMap.put("dw2.8xlarge", "dc1.8xlarge");
    }

    private String currentRedshiftUsageType(String usageType) {
    	if (redshiftUsageTypeMap.containsKey(usageType))
    		return redshiftUsageTypeMap.get(usageType);
    	return usageType;
    }

    protected static class ReformedMetaData{
        public final Region region;
        public final Product product;
        public final Operation operation;
        public final UsageType usageType;
        public ReformedMetaData(Region region, Product product, Operation operation, UsageType usageType) {
            this.region = region;
            this.product = product;
            this.operation = operation;
            this.usageType = usageType;
        }
        
        public String toString() {
            return "\"" + region + "\",\"" + product + "\",\"" + operation + "\",\"" + usageType + "\"";

        }
    }
    
    private String getUserTagsString(String[] lineItem) {
    	StringBuilder sb = new StringBuilder();
    	boolean first = true;
    	for (int i = resourceIndex + 1; i < lineItem.length; i++) {
    		if (lineItem[i].isEmpty()) {
    			continue;
    		}
    		sb.append((first ? "" : "|") + header.get(i).substring("user:".length()) + "=" + lineItem[i]);
    		first = false;
    	}
    	return sb.toString();
    }
}
