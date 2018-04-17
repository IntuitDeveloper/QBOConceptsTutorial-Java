package com.intuit.developer.tutorials.controller;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.List;

import javax.servlet.http.HttpSession;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intuit.ipp.core.Context;
import com.intuit.ipp.core.IEntity;
import com.intuit.ipp.core.ServiceType;
import com.intuit.ipp.data.Account;
import com.intuit.ipp.data.AccountTypeEnum;
import com.intuit.ipp.data.Customer;
import com.intuit.ipp.data.DiscountLineDetail;
import com.intuit.ipp.data.Estimate;
import com.intuit.ipp.data.IntuitEntity;
import com.intuit.ipp.data.Invoice;
import com.intuit.ipp.data.Item;
import com.intuit.ipp.data.ItemTypeEnum;
import com.intuit.ipp.data.Line;
import com.intuit.ipp.data.LineDetailTypeEnum;
import com.intuit.ipp.data.ReferenceType;
import com.intuit.ipp.data.SalesItemLineDetail;
import com.intuit.ipp.security.OAuth2Authorizer;
import com.intuit.ipp.services.QueryResult;
import com.intuit.ipp.util.Config;
import com.intuit.ipp.util.DateUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.log4j.Logger;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import com.intuit.developer.tutorials.client.OAuth2PlatformClientFactory;
import com.intuit.developer.tutorials.helper.QBOServiceHelper;
import com.intuit.ipp.data.Error;
import com.intuit.ipp.exception.FMSException;
import com.intuit.ipp.exception.InvalidTokenException;
import com.intuit.ipp.services.DataService;

/**
 * @author dderose
 *
 */
@Controller
public class JobsController {

	private static final String MINOR_VERSION = "4";

	private static final String ACCOUNT_QUERY = "select * from Account where AccountType='%s' maxresults 1";

	@Autowired
	OAuth2PlatformClientFactory factory;
	
	@Autowired
    public QBOServiceHelper helper;

	
	private static final Logger logger = Logger.getLogger(JobsController.class);
	
	
	/**
     * Sample QBO API call using OAuth2 tokens
     * 
     * @param session
     * @return
     */
	@ResponseBody
    @RequestMapping("/jobs")
    public String callJobsConcept(HttpSession session) {

    	String realmId = (String)session.getAttribute("realmId");
    	if (StringUtils.isEmpty(realmId)) {
    		return new JSONObject().put("response","No realm ID.  QBO calls only work if the accounting scope was passed!").toString();
    	}
    	String accessToken = (String)session.getAttribute("access_token");
    	
        try {
        	
        	//get DataService
    		DataService service = getDataService(realmId, accessToken, MINOR_VERSION);

			//add customer
			Customer customer = getCustomerWithMandatoryFields();

			final Customer customerResult = service.add(customer);

			//add item

			Item item = getItemWithMandatoryFields(service);

			final Item itemResult = service.add(item);
    		
    		//create estimate
			Estimate estimate = getEstimateWithMandatoryFields(itemResult, customerResult, service);

			final Estimate createdEstimate = service.add(estimate);

			//update estimate -change amt

			createdEstimate.setTotalAmt(new BigDecimal("400.00"));

			final Estimate updatedEstimate = service.update(createdEstimate);

			//create invoice using estimate data

			Invoice invoice = getInvoiceFieldsFromEstimate(estimate);

			final Invoice createdInvoice = service.add(invoice);

			//update invoice to add discount
            Line discountLine = createDiscountLine(service);
			createdInvoice.getLine().add(discountLine);

			//update the invoice with the discount line
			final Invoice updatedInvoice = service.update(createdInvoice);

			//return response back
    		return createResponse(updatedInvoice);
		} catch (InvalidTokenException e) {
			return new JSONObject().put("response","InvalidToken - Refresh token and try again").toString();
		} catch (FMSException e) {
			List<Error> list = e.getErrorList();
			list.forEach(error -> logger.error("Error while calling the API :: " + error.getMessage()));
			return new JSONObject().put("response","Failed").toString();
		}
    }

    private Line createDiscountLine(DataService service) throws FMSException {
        DiscountLineDetail discountLineDetail = new DiscountLineDetail();

        discountLineDetail.setPercentBased(false);
        discountLineDetail.setDiscountAccountRef(createRef(findAccountByType(AccountTypeEnum.INCOME, service)));

        Line discountLine = new Line();

        discountLine.setDiscountLineDetail(discountLineDetail);
        discountLine.setDetailType(LineDetailTypeEnum.DISCOUNT_LINE_DETAIL);
        discountLine.setAmount(new BigDecimal("10.00"));
        return discountLine;
    }

    /**
	 * This method is an override with duplication of the QBOServiceHelper
	 * to pass in a different minor version.  This is due to an invoice update
	 * failing with the discount line for minor version "23" (SDK default), hence
	 * overriding with the minor version "4"
	 *
	 * @param realmId
	 * @param accessToken
	 * @return
	 * @throws FMSException
	 */
	private DataService getDataService(String realmId, String accessToken, String minorVersion) throws FMSException {

		//get DataService
		String url = factory.getPropertyValue("IntuitAccountingAPIHost") + "/v3/company";

		Config.setProperty(Config.BASE_URL_QBO, url);
		//create oauth object
		OAuth2Authorizer oauth = new OAuth2Authorizer(accessToken);

		Context context = new Context(oauth, ServiceType.QBO, realmId);
		context.setMinorVersion(minorVersion);

		// create dataservice
		return new DataService(context);
	}


	private Invoice getInvoiceFieldsFromEstimate(Estimate estimate) {
		Invoice invoice = new Invoice();

		invoice.setCustomerRef(estimate.getCustomerRef());

		List<Line> invoiceLine = new ArrayList<>();


		Line line = null;

		if(estimate.getLine()!=null && !estimate.getLine().isEmpty()) {
			line = estimate.getLine().get(0);
		}

		SalesItemLineDetail silDetails = new SalesItemLineDetail();

		if(estimate.getLine()!=null && !estimate.getLine().isEmpty()
				&& estimate.getLine().get(0).getSalesItemLineDetail()!=null ) {
			silDetails.setItemRef(estimate.getLine().get(0).getSalesItemLineDetail().getItemRef());
		}

		line.setSalesItemLineDetail(silDetails);
		invoiceLine.add(line);
		invoice.setLine(invoiceLine);

		return invoice;
	}

	private Estimate getEstimateWithMandatoryFields(Item item, Customer customer, DataService service) throws FMSException {
		Estimate estimate = new Estimate();
		estimate.setDocNumber(RandomStringUtils.randomNumeric(4));
		try {
			estimate.setTxnDate(DateUtils.getCurrentDateTime());
		} catch (ParseException e) {
			throw new FMSException("ParseException while getting current date.");
		}
		try {
			estimate.setExpirationDate(DateUtils.getDateWithNextDays(15));
		} catch (ParseException e) {
			throw new FMSException("ParseException while getting current date + 15 days.");
		}

		Line line1 = new Line();
		line1.setLineNum(new BigInteger("1"));
		line1.setAmount(new BigDecimal("300.00"));
		line1.setDetailType(LineDetailTypeEnum.SALES_ITEM_LINE_DETAIL);

		SalesItemLineDetail salesItemLineDetail1 = new SalesItemLineDetail();
		salesItemLineDetail1.setItemRef(createRef(item));

		ReferenceType taxCodeRef1 = new ReferenceType();
		taxCodeRef1.setValue("NON");
		salesItemLineDetail1.setTaxCodeRef(taxCodeRef1);
		line1.setSalesItemLineDetail(salesItemLineDetail1);

		List<Line> lines1 = new ArrayList<>();
		lines1.add(line1);
		estimate.setLine(lines1);


		Account depositAccount = findAccountByType(AccountTypeEnum.BANK, service);
		estimate.setDepositToAccountRef(createRef(depositAccount));

		estimate.setCustomerRef(createRef(customer));

		estimate.setApplyTaxAfterDiscount(false);
		estimate.setTotalAmt(new BigDecimal("300.00"));
		estimate.setPrivateNote("Accurate Estimate");

		return estimate;
	}

	private Account findAccountByType(AccountTypeEnum accountTypeEnum, DataService service) throws FMSException {

		final QueryResult queryResult = service.executeQuery(String.format(ACCOUNT_QUERY, accountTypeEnum.value()));

		final List<? extends IEntity> entities = queryResult.getEntities();

		Account account = null;

		if(!entities.isEmpty()) {
			account = (Account)entities.get(0);
		}
		return account;
	}

	private Customer getCustomerWithMandatoryFields() {
		Customer customer = new Customer();
		customer.setDisplayName(RandomStringUtils.randomAlphanumeric(6));
		return customer;
	}

	private Item getItemWithMandatoryFields(DataService service) throws FMSException {
		Item item = new Item();
		item.setName("Item" + RandomStringUtils.randomAlphanumeric(5));
		item.setActive(true);
		item.setTaxable(false);
		item.setUnitPrice(new BigDecimal("200"));
		item.setType(ItemTypeEnum.SERVICE);

		item.setIncomeAccountRef(createRef(findAccountByType(AccountTypeEnum.INCOME, service)));
		item.setExpenseAccountRef(createRef(findAccountByType(AccountTypeEnum.EXPENSE, service)));
		return item;
	}

	private ReferenceType createRef(IntuitEntity entity) {
		ReferenceType referenceType = new ReferenceType();
		referenceType.setValue(entity.getId());
		return referenceType;
	}

//	private ReferenceType createItemRef(Item item) {
//		ReferenceType referenceType = new ReferenceType();
//		referenceType.setName(item.getName());
//		referenceType.setValue(item.getId());
//		return referenceType;
//	}
//
//	private ReferenceType createCustomerRef(Customer customer) {
//		ReferenceType referenceType = new ReferenceType();
//		referenceType.setName(customer.getFullyQualifiedName());
//		referenceType.setValue(customer.getId());
//		return referenceType;
//	}

	private String createResponse(Object entity) {
		ObjectMapper mapper = new ObjectMapper();
		String jsonInString;
		try {
			jsonInString = mapper.writeValueAsString(entity);
		} catch (JsonProcessingException e) {
			return createErrorResponse(e);
		} catch (Exception e) {
			return createErrorResponse(e);
		}
		return jsonInString;
	}

	private String createErrorResponse(Exception e) {
		logger.error("Exception while getting company info ", e);
		return new JSONObject().put("response","Failed").toString();
	}
}
