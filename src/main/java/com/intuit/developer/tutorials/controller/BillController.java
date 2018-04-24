package com.intuit.developer.tutorials.controller;

import java.math.BigDecimal;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.List;

import javax.servlet.http.HttpSession;

import org.apache.commons.lang.RandomStringUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intuit.developer.tutorials.client.OAuth2PlatformClientFactory;
import com.intuit.developer.tutorials.helper.QBOServiceHelper;
import com.intuit.ipp.core.IEntity;
import com.intuit.ipp.data.Account;
import com.intuit.ipp.data.AccountBasedExpenseLineDetail;
import com.intuit.ipp.data.AccountClassificationEnum;
import com.intuit.ipp.data.AccountSubTypeEnum;
import com.intuit.ipp.data.AccountTypeEnum;
import com.intuit.ipp.data.Bill;
import com.intuit.ipp.data.BillPayment;
import com.intuit.ipp.data.BillPaymentCheck;
import com.intuit.ipp.data.BillPaymentTypeEnum;
import com.intuit.ipp.data.CheckPayment;
import com.intuit.ipp.data.Error;
import com.intuit.ipp.data.IntuitEntity;
import com.intuit.ipp.data.Line;
import com.intuit.ipp.data.LineDetailTypeEnum;
import com.intuit.ipp.data.LinkedTxn;
import com.intuit.ipp.data.ReferenceType;
import com.intuit.ipp.data.TxnTypeEnum;
import com.intuit.ipp.data.Vendor;
import com.intuit.ipp.data.VendorCredit;
import com.intuit.ipp.exception.FMSException;
import com.intuit.ipp.exception.InvalidTokenException;
import com.intuit.ipp.services.DataService;
import com.intuit.ipp.services.QueryResult;


/**
 * @author dderose
 *
 */
@Controller
public class BillController {
	
	@Autowired
	OAuth2PlatformClientFactory factory;
	
	@Autowired
    public QBOServiceHelper helper;
	
	private static final Logger logger = Logger.getLogger(BillController.class);
	
	private static final String ACCOUNT_QUERY = "select * from Account where AccountType='%s' maxresults 1";
	
	
	/**
     * Sample QBO API call using OAuth2 tokens
     * 
     * @param session
     * @return
     */
	@ResponseBody
    @RequestMapping("/bill")
    public String callBillingConcept(HttpSession session) throws FMSException, ParseException{

    	String realmId = (String)session.getAttribute("realmId");
    	if (StringUtils.isEmpty(realmId)) {
    		return new JSONObject().put("response","No realm ID.  QBO calls only work if the accounting scope was passed!").toString();
    	}
    	String accessToken = (String)session.getAttribute("access_token");
    	
    	try {
        	
        	//get DataService
    		DataService service = helper.getDataService(realmId, accessToken);
			
    		//add vendor
    		Vendor vendor = getVendorFields();
			Vendor vendorOut = service.add(vendor);

			//add bill
			Bill bill = getBillFields(service, vendorOut);
			Bill billOut = service.add(bill);

    		//make bill payment
			BillPayment billPayment = getBillPaymentFields(service, billOut);
			service.add(billPayment);

    		//add vendor credit
			VendorCredit vendorCredit = getVendorCreditFields(service, vendorOut);
			VendorCredit vendorCreditOut = service.add(vendorCredit);
    		
    		//return response back
    		return createResponse(vendorCreditOut);
			
		}
	        
        catch (InvalidTokenException e) {			
			return new JSONObject().put("response","InvalidToken - Refreshtoken and try again").toString();
		} catch (FMSException e) {
			List<Error> list = e.getErrorList();
			list.forEach(error -> logger.error("Error while calling the API :: " + error.getMessage()));
			return new JSONObject().put("response","Failed").toString();
		}
    }

	/**
	 * Prepare Vendor request
	 * @return
	 */
	private Vendor getVendorFields() {
		Vendor vendor = new Vendor();
		// Mandatory Fields
		vendor.setDisplayName(RandomStringUtils.randomAlphanumeric(8));
		return vendor;
	}

	/**
	 * Prepare Bill request
	 * @param service
	 * @param vendor
	 * @return
	 * @throws FMSException
	 */
	private  Bill getBillFields(DataService service, Vendor vendor) throws FMSException {

		Bill bill = new Bill();
		bill.setVendorRef(createRef(vendor));

		Account liabilityAccount = getLiabilityBankAccount(service);
		bill.setAPAccountRef(createRef(liabilityAccount));

		Line line1 = new Line();
		line1.setAmount(new BigDecimal("30.00"));
		line1.setDetailType(LineDetailTypeEnum.ACCOUNT_BASED_EXPENSE_LINE_DETAIL);
		AccountBasedExpenseLineDetail detail = new AccountBasedExpenseLineDetail();
		Account account = getExpenseBankAccount(service);
		ReferenceType expenseAccountRef = createRef(account);
		detail.setAccountRef(expenseAccountRef);
		line1.setAccountBasedExpenseLineDetail(detail);

		List<Line> lines1 = new ArrayList<Line>();
		lines1.add(line1);
		bill.setLine(lines1);

		bill.setTotalAmt(new BigDecimal("30.00"));

		return bill;
	}

	/**
	 * Prepare BillPayment request
	 * @param service
	 * @param bill
	 * @return
	 * @throws FMSException
	 */
	private BillPayment getBillPaymentFields(DataService service, Bill bill) throws FMSException {
		BillPayment billPayment = new BillPayment();

		billPayment.setVendorRef(bill.getVendorRef());

		Line line1 = new Line();
		line1.setAmount(new BigDecimal("30"));
		List<LinkedTxn> linkedTxnList1 = new ArrayList<LinkedTxn>();
		LinkedTxn linkedTxn1 = new LinkedTxn();
		linkedTxn1.setTxnId(bill.getId());
		linkedTxn1.setTxnType(TxnTypeEnum.BILL.value());
		linkedTxnList1.add(linkedTxn1);
		line1.setLinkedTxn(linkedTxnList1);

		List<Line> lineList = new ArrayList<Line>();
		lineList.add(line1);
		billPayment.setLine(lineList);

		BillPaymentCheck billPaymentCheck = new BillPaymentCheck();
		Account bankAccount = getCheckBankAccount(service);
		billPaymentCheck.setBankAccountRef(createRef(bankAccount));

		billPaymentCheck.setCheckDetail(getCheckPayment());

		billPayment.setCheckPayment(billPaymentCheck);
		billPayment.setPayType(BillPaymentTypeEnum.CHECK);
		billPayment.setTotalAmt(new BigDecimal("30"));
		return billPayment;
	}
	
	/**
	 * Prepare VendorCredit Request
	 * @param service
	 * @param vendor
	 * @return
	 * @throws FMSException
	 */
	private  VendorCredit getVendorCreditFields(DataService service, Vendor vendor) throws FMSException {

		VendorCredit vendorCredit = new VendorCredit();
		vendorCredit.setVendorRef(createRef(vendor));

		Account account = getLiabilityBankAccount(service);
		vendorCredit.setAPAccountRef(createRef(account));

		Line line1 = new Line();
		line1.setAmount(new BigDecimal("30.00"));
		line1.setDetailType(LineDetailTypeEnum.ACCOUNT_BASED_EXPENSE_LINE_DETAIL);
		AccountBasedExpenseLineDetail detail = new AccountBasedExpenseLineDetail();
		Account expenseAccount = getExpenseBankAccount(service);
		detail.setAccountRef(createRef(expenseAccount));
		line1.setAccountBasedExpenseLineDetail(detail);

		List<Line> lines1 = new ArrayList<Line>();
		lines1.add(line1);
		vendorCredit.setLine(lines1);

		return vendorCredit;
	}

	/**
	 * Get Bank Account
	 * 
	 * @param service
	 * @return
	 * @throws FMSException
	 */
	private  Account getCheckBankAccount(DataService service) throws FMSException {
		QueryResult queryResult = service.executeQuery(String.format(ACCOUNT_QUERY, AccountTypeEnum.BANK.value()));
		List<? extends IEntity> entities = queryResult.getEntities();
		if(!entities.isEmpty()) {
			return (Account)entities.get(0);
		}
		return createBankAccount(service);
	}
	
	/**
	 * Create Bank Account
	 * @param service
	 * @return
	 * @throws FMSException
	 * @throws ParseException
	 */
	private  Account createBankAccount(DataService service) throws FMSException {
		Account account = new Account();
		account.setName("Ba" + RandomStringUtils.randomAlphanumeric(7));
		account.setClassification(AccountClassificationEnum.ASSET);
		account.setAccountType(AccountTypeEnum.BANK);	
		return service.add(account);
	}

	/**
	 * Get Expense Account
	 * @param service
	 * @return
	 * @throws FMSException
	 */
	private  Account getExpenseBankAccount(DataService service) throws FMSException {

		QueryResult queryResult = service.executeQuery(String.format(ACCOUNT_QUERY, AccountTypeEnum.EXPENSE.value()));
		List<? extends IEntity> entities = queryResult.getEntities();
		if(!entities.isEmpty()) {
			return (Account)entities.get(0);
		}
		return createExpenseBankAccount(service);
	}

	/**
	 * Create Expense Account
	 * @param service
	 * @return
	 * @throws FMSException
	 */
	private Account createExpenseBankAccount(DataService service) throws FMSException {
		Account account = new Account();
		account.setName("Expense" + RandomStringUtils.randomAlphabetic(5));
		account.setClassification(AccountClassificationEnum.EXPENSE);
		account.setAccountType(AccountTypeEnum.EXPENSE);
		account.setAccountSubType(AccountSubTypeEnum.ADVERTISING_PROMOTIONAL.value());
		
		return service.add(account);
	}

	/**
	 * Get AP account
	 * @param service
	 * @return
	 * @throws FMSException
	 */
	private Account getLiabilityBankAccount(DataService service) throws FMSException {

		QueryResult queryResult = service.executeQuery(String.format(ACCOUNT_QUERY, AccountTypeEnum.ACCOUNTS_PAYABLE.value()));
		List<? extends IEntity> entities = queryResult.getEntities();
		if(!entities.isEmpty()) {
			return (Account)entities.get(0);
		}
		return createLiabilityBankAccount(service);
	}

	/**
	 * Create AP account
	 * @param service
	 * @return
	 * @throws FMSException
	 */
	private Account createLiabilityBankAccount(DataService service) throws FMSException {
		Account account = new Account();
		account.setName("Equity" + RandomStringUtils.randomAlphabetic(5));
		account.setClassification(AccountClassificationEnum.LIABILITY);
		account.setAccountType(AccountTypeEnum.ACCOUNTS_PAYABLE);
		account.setAccountSubType(AccountSubTypeEnum.ACCOUNTS_PAYABLE.value());
		
		return service.add(account);
	}


	/**
	 * Prepare CheckPayment request
	 * @return
	 * @throws FMSException
	 */
	private CheckPayment getCheckPayment() throws FMSException {
		String uuid = RandomStringUtils.randomAlphanumeric(8);

		CheckPayment checkPayment = new CheckPayment();
		checkPayment.setAcctNum("AccNum" + uuid);
		checkPayment.setBankName("BankName" + uuid);
		checkPayment.setCheckNum("CheckNum" + uuid);
		checkPayment.setNameOnAcct("Name" + uuid);
		checkPayment.setStatus("Status" + uuid);
		return checkPayment;
	}
	
	/**
	 * Creates reference type for an entity
	 * 
	 * @param entity - IntuitEntity object inherited by each entity
	 * @return
	 */
	private ReferenceType createRef(IntuitEntity entity) {
		ReferenceType referenceType = new ReferenceType();
		referenceType.setValue(entity.getId());
		return referenceType;
	}

	/**
	 * Map object to json string
	 * @param entity
	 * @return
	 */
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
		logger.error("Exception while calling QBO ", e);
		return new JSONObject().put("response","Failed").toString();
	}

}

