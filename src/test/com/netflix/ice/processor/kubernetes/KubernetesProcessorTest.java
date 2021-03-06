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
package com.netflix.ice.processor.kubernetes;

import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.junit.Test;

import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.netflix.ice.basic.BasicProductService;
import com.netflix.ice.basic.BasicReservationService;
import com.netflix.ice.common.IceOptions;
import com.netflix.ice.common.ProductService;
import com.netflix.ice.common.ResourceService;
import com.netflix.ice.common.TagGroup;
import com.netflix.ice.common.WorkBucketDataConfig;
import com.netflix.ice.processor.ProcessorConfig;
import com.netflix.ice.processor.ReadWriteData;
import com.netflix.ice.processor.ReservationService;
import com.netflix.ice.processor.config.AccountConfig;
import com.netflix.ice.processor.config.BillingDataConfig;
import com.netflix.ice.processor.config.KubernetesConfig;
import com.netflix.ice.processor.pricelist.PriceListService;
import com.netflix.ice.tag.Account;
import com.netflix.ice.tag.Operation;
import com.netflix.ice.tag.Product;
import com.netflix.ice.tag.Region;
import com.netflix.ice.tag.ResourceGroup;
import com.netflix.ice.tag.ResourceGroup.ResourceException;
import com.netflix.ice.tag.UsageType;
import com.netflix.ice.tag.Zone;
import com.netflix.ice.tag.Zone.BadZone;

public class KubernetesProcessorTest {
	private static final String resourceDir = "src/test/resources/";
	private static final int testDataHour = 395;

    private ProductService productService = new BasicProductService();
    
	class TestConfig extends ProcessorConfig {

		public TestConfig(Properties properties,
				AWSCredentialsProvider credentialsProvider,
				ProductService productService,
				ReservationService reservationService,
				PriceListService priceListService, String[] formulae)
				throws Exception {
			super(properties, credentialsProvider, productService,
					reservationService,
					priceListService);
			
			initKubernetesConfigs(formulae);
		}
		
		private void initKubernetesConfigs(String[] formulae) {
	        StringBuilder formulaeYaml = new StringBuilder(100);
	        for (String f: formulae) {
	        	if (formulaeYaml.length() > 0)
	        		formulaeYaml.append(",");
	        	
	        	formulaeYaml.append("'" + f + "'");	        	
	        }
			String yaml = 
					"kubernetes:\n" + 
					"  - bucket: k8s-report-bucket\n" + 
					"    prefix: hourly/kubernetes\n" + 
					"    clusterNameFormulae: [ " + formulaeYaml.toString() + " ]\n" + 
					"    computeTag: Role\n" + 
					"    computeValue: compute\n" + 
					"    namespaceTag: Namespace\n" + 
					"    tags: [ UserTag1, UserTag2 ]\n" + 
					"";
			try {
				BillingDataConfig b = new BillingDataConfig(yaml);
		    	kubernetesConfigs = Lists.newArrayList();
	        	List<KubernetesConfig> k = b.getKubernetes();
	        	if (k != null)
	        		kubernetesConfigs.addAll(k);
			} catch (IOException e) {
				e.printStackTrace();
			}			
		}
		
		@Override
	    protected void initZones() {			
		}
		
		@Override
	    protected Map<String, AccountConfig> getAccountsFromOrganizations() {
			Map<String, AccountConfig> accounts = Maps.newHashMap();
			com.amazonaws.services.organizations.model.Account a = new com.amazonaws.services.organizations.model.Account().withId("123456789012").withName("Account1");
			accounts.put("123456789012", new AccountConfig(a, null, null, null));
			return accounts;
		}
		
		@Override
		protected void processBillingDataConfig(Map<String, AccountConfig> accountConfigs) {
		}
		
		@Override
		protected WorkBucketDataConfig downloadWorkBucketDataConfig(boolean force) {
			return null;
		}
	}
	
	class TestKubernetesProcessor extends KubernetesProcessor {

		public TestKubernetesProcessor(ProcessorConfig config, DateTime start) throws IOException {
			super(config, start);
		}

		@Override
		protected List<KubernetesReport> getReportsToProcess(DateTime start) throws IOException {
	        List<KubernetesReport> filesToProcess = Lists.newArrayList();
        	filesToProcess.add(new TestKubernetesReport(config.kubernetesConfigs.get(0), config.resourceService));
			return filesToProcess;
		}
	}
	
	class TestKubernetesReport extends KubernetesReport {

		public TestKubernetesReport(KubernetesConfig config, ResourceService resourceService) {
			super(null, null, new DateTime("2019-01", DateTimeZone.UTC), config, resourceService);

			File file = new File(resourceDir, "kubernetes-2019-01.csv");
			readFile(file);			
		}
	}
	
	private KubernetesProcessor newKubernetesProcessor(String[] formulae) throws Exception {
		Properties props = new Properties();
		
		AWSCredentialsProvider credentialsProvider = new DefaultAWSCredentialsProviderChain();
        
        ReservationService reservationService = new BasicReservationService(null, null);
        
        props.setProperty(IceOptions.START_MONTH, "2019-01");
        props.setProperty(IceOptions.WORK_S3_BUCKET_NAME, "foo");
        props.setProperty(IceOptions.WORK_S3_BUCKET_REGION, "us-east-1");
        props.setProperty(IceOptions.BILLING_S3_BUCKET_NAME, "bar");
        props.setProperty(IceOptions.BILLING_S3_BUCKET_REGION, "us-east-1");
        props.setProperty(IceOptions.CUSTOM_TAGS, "Cluster,Role,Namespace,Environment");
        
		ProcessorConfig config = new TestConfig(
				props,
	            credentialsProvider,
	            productService,
	            reservationService,
	            null, formulae);
		
		return new TestKubernetesProcessor(config, null);
	}
	
	private TagGroup getTagGroup(String clusterName) throws BadZone, ResourceException {
        List<Account> accounts = Lists.newArrayList(new Account("123456789012", "Account1", null));

		Zone us_west_2a = Region.US_WEST_2.getZone("us-west-2a");
		Product ec2Instance = productService.getProduct(Product.Code.Ec2Instance);
		UsageType usageType = UsageType.getUsageType("r5.4xlarge", "hours");
		
		String[] tags = new String[]{ clusterName, "compute", "", "Dev", };
		ResourceGroup resourceGroup = ResourceGroup.getResourceGroup(tags);
		TagGroup tg = TagGroup.getTagGroup(accounts.get(0), Region.US_WEST_2, us_west_2a, ec2Instance, Operation.ondemandInstances, usageType, resourceGroup);
		return tg;
	}
	
	@Test
	public void testProcessHourClusterData() throws Exception {
		KubernetesProcessor kp = newKubernetesProcessor(new String[]{"Cluster"});
		KubernetesConfig kc = new KubernetesConfig();
		kc.setTags(new ArrayList<String>());
		kc.setNamespaceTag("Namespace");
		TestKubernetesReport tkr = new TestKubernetesReport(kc, kp.config.resourceService);
		
		// Test the data for cluster "dev-usw2a"
		String clusterName = "dev-usw2a";
		
		TagGroup tg = getTagGroup(clusterName);
		ReadWriteData costData = new ReadWriteData();
		costData.put(0, tg, 40.0);
		
		List<String[]> hourClusterData = tkr.getData(clusterName, testDataHour);
		kp.processHourClusterData(costData, 0, tg, clusterName, tkr, hourClusterData);
		
		String[] atags = new String[]{ clusterName, "compute", "kube-system", "Dev", };
		ResourceGroup arg = ResourceGroup.getResourceGroup(atags);
		TagGroup atg = TagGroup.getTagGroup(tg.account, tg.region, tg.zone, tg.product, tg.operation, tg.usageType, arg);
		
		Double allocatedCost = costData.get(0, atg);
		assertNotNull("No allocated cost for kube-system namespace", allocatedCost);
		assertEquals("Incorrect allocated cost", 0.4133, allocatedCost, 0.0001);
		String[] unusedTags = new String[]{ clusterName, "compute", "unused", "Dev", };
		TagGroup unusedTg = TagGroup.getTagGroup(tg.account, tg.region, tg.zone, tg.product, tg.operation, tg.usageType, ResourceGroup.getResourceGroup(unusedTags));
		double unusedCost = costData.get(0, unusedTg);
		assertEquals("Incorrect unused cost", 21.1983, unusedCost, 0.0001);
		
		// Add up all the cost values to see if we get back to 40.0
		double total = 0.0;
		Map<TagGroup, Double> hourZeroCostData = costData.getData(0);
		for (double v: hourZeroCostData.values())
			total += v;
		assertEquals("Incorrect total cost when adding unused and allocated values", 40.0, total, 0.001);		
	}
	
	@Test
	public void testProcess() throws Exception {
		
		// Test with three formulae and make sure we only process each cluster once.
		// The cluster names should match the forumlae as follows:
		//  "dev-usw2a" --> formula 1
		//  "k8s-dev-usw2a" --> formula 2
		//  "k8s-usw2a --> formula 3
		String[] clusterTags = new String[]{ "dev-usw2a", "k8s-prod-usw2a", "k8s-usw2a" };
		String[] formulae = new String[]{
			"Cluster",											// 1. use the cluster name directly
			"Cluster.regex(\"k8s-(.*)\")",						// 2. Strip off the leading "k8s-"
			"Environment.toLower()+Cluster.regex(\"k8s(-.*)\")", // 3. Get the environment string and join with suffix of cluster
			"Cluster", 											// 4. repeat of formula 1 to verify we don't process cluster twice
		};
		KubernetesProcessor kp = newKubernetesProcessor(formulae);
		TagGroup[] tgs = new TagGroup[clusterTags.length];
		ReadWriteData costData = new ReadWriteData();
		Map<TagGroup, Double> hourCostData = costData.getData(testDataHour);
		
		for (int i = 0; i < clusterTags.length; i++) {
			tgs[i] = getTagGroup(clusterTags[i]);
			costData.put(testDataHour, tgs[i], 40.0);
		}
						
		kp.process(costData);
		
		double[] expectedAllocatedCosts = new double[]{ 0.4133, 0.4324, 0.4133 };
		double[] expectedUnusedCosts = new double[]{ 21.1983, 12.0014, 21.1983 };
		
		for (int i = 0; i < clusterTags.length; i++) {
			String clusterTag = clusterTags[i];
			TagGroup tg = tgs[i];
			
			String[] atags = new String[]{ clusterTags[i], "compute", "kube-system", "Dev", };
			ResourceGroup arg = ResourceGroup.getResourceGroup(atags);
			TagGroup atg = TagGroup.getTagGroup(tgs[i].account, tg.region, tg.zone, tg.product, tg.operation, tg.usageType, arg);
			
			Double allocatedCost = hourCostData.get(atg);
			assertNotNull("No allocated cost for kube-system namespace with cluster tag " + clusterTag, allocatedCost);
			assertEquals("Incorrect allocated cost with cluster tag " + clusterTag, expectedAllocatedCosts[i], allocatedCost, 0.0001);
			String[] unusedTags = new String[]{ clusterTag, "compute", "unused", "Dev", };
			TagGroup unusedTg = TagGroup.getTagGroup(tg.account, tg.region, tg.zone, tg.product, tg.operation, tg.usageType, ResourceGroup.getResourceGroup(unusedTags));
			double unusedCost = hourCostData.get(unusedTg);
			assertEquals("Incorrect unused cost with cluster tag " + clusterTag, expectedUnusedCosts[i], unusedCost, 0.0001);
		}
				
		// Add up all the cost values to see if we get back to 120.0 (Three tagGroups with 40.0 each)
		double total = 0.0;
		for (double v: hourCostData.values())
			total += v;
		assertEquals("Incorrect total cost when adding unused and allocated values", 120.0, total, 0.001);		
	}

}
