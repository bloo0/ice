package com.netflix.ice.processor;

import static org.junit.Assert.*;

import java.io.IOException;

import org.junit.Test;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;

public class BillingDataConfigTest {

	@Test
	public void testConstructFromJson() throws JsonParseException, JsonMappingException, IOException {
		String json = "{\n" + 
				"	\"accounts\": [\n" + 
				"		{\n" + 
				"			\"id\": \"123456789012\",\n" + 
				"			\"name\": \"act1\",\n" + 
				"			\"tags\": {\n" + 
				"				\"Tag1\": \"tag1value\"\n" + 
				"			},\n" + 
				"			\"riProducts\": [ \"ec2\" ],\n" + 
				"			\"role\": \"ice\",\n" + 
				"			\"externalId\": \"12345\"\n" + 
				"		}\n" + 
				"	],\n" + 
				"	\"tags\":[\n" + 
				"		{\n" +
				"           \"name\": \"Environment\",\n" + 
				"			\"aliases\": [ \"env\" ],\n" + 
				"			\"values\": {\n" + 
				"				\"Prod\": [ \"production\", \"prd\" ]\n" + 
				"			}\n" + 
				"		}\n" + 
				"	]\n" +
				"}";
		
		BillingDataConfig c = new BillingDataConfig(json);
		assertEquals("Wrong number of accounts", 1, c.accounts.size());
		AccountConfig account = c.accounts.get(0);
		assertEquals("Wrong account id", "123456789012", account.id);
		assertEquals("Wrong account name", "act1", account.name);
		
		assertEquals("Wrong number of account tags", 1, account.tags.size());
		assertTrue("Map doesn't have tag", account.tags.containsKey("Tag1"));
		String tag = account.tags.get("Tag1");
		assertEquals("Wrong tag value", "tag1value", tag);
		assertEquals("Wrong number of RI Products", 1, account.riProducts.size());
		assertEquals("Wrong RI Product", "ec2", account.riProducts.get(0));
		assertEquals("Wrong role", "ice", account.role);
		assertEquals("Wrong externalId", "12345", account.externalId);

		// Test without an account name
		json = json.replace("\"name\": \"act1\",\n", "");
		c = new BillingDataConfig(json);
		assertNull("Should have null account name", c.accounts.get(0).name);
	}
	
	@Test
	public void testConstructFromYaml() throws JsonParseException, JsonMappingException, IOException {
		String yaml = 
				"accounts:\n" + 
				"  - id: 123456789012\n" + 
				"    name: act1\n" + 
				"    tags:\n" + 
				"        Tag1: tag1value\n" + 
				"    riProducts: [ec2]\n" + 
				"    role: ice\n" + 
				"    externalId: 12345\n" + 
				"tags:\n" + 
				"  - name: Environment\n" + 
				"    aliases: [env]\n" + 
				"    values:\n" + 
				"        Prod: [production, prd]\n";
						
		BillingDataConfig c = new BillingDataConfig(yaml);
		assertEquals("Wrong number of accounts", 1, c.accounts.size());
		AccountConfig account = c.accounts.get(0);
		assertEquals("Wrong account id", "123456789012", account.id);
		assertEquals("Wrong account name", "act1", account.name);
		
		assertEquals("Wrong number of account default tags", 1, account.tags.size());
		assertTrue("Map doesn't have default tag", account.tags.containsKey("Tag1"));
		String tag = account.tags.get("Tag1");
		assertEquals("Wrong default tag value", "tag1value", tag);
		assertEquals("Wrong number of RI Products", 1, account.riProducts.size());
		assertEquals("Wrong RI Product", "ec2", account.riProducts.get(0));
		assertEquals("Wrong role", "ice", account.role);
		assertEquals("Wrong externalId", "12345", account.externalId);

		// Test without an account name
		yaml = yaml.replace("    name: act1\n", "");
		c = new BillingDataConfig(yaml);
		assertNull("Should have null account name", c.accounts.get(0).name);
	}
}