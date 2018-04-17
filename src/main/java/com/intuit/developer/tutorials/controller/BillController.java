package com.intuit.developer.tutorials.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intuit.developer.tutorials.client.OAuth2PlatformClientFactory;
import com.intuit.developer.tutorials.helper.QBOServiceHelper;
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
import com.intuit.ipp.data.EmailAddress;
import com.intuit.ipp.data.Error;
import com.intuit.ipp.data.GlobalTaxCalculationEnum;
import com.intuit.ipp.data.Line;
import com.intuit.ipp.data.LineDetailTypeEnum;
import com.intuit.ipp.data.LinkedTxn;
import com.intuit.ipp.data.PhysicalAddress;
import com.intuit.ipp.data.PrintStatusEnum;
import com.intuit.ipp.data.ReferenceType;
import com.intuit.ipp.data.TelephoneNumber;
import com.intuit.ipp.data.Term;
import com.intuit.ipp.data.TxnTypeEnum;
import com.intuit.ipp.data.Vendor;
import com.intuit.ipp.data.VendorCredit;
import com.intuit.ipp.data.WebSiteAddress;
import com.intuit.ipp.exception.FMSException;
import com.intuit.ipp.exception.InvalidTokenException;
import com.intuit.ipp.services.DataService;
import com.intuit.ipp.util.DateUtils;

import org.apache.commons.lang.RandomStringUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.math.BigDecimal;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import javax.servlet.http.HttpSession;


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
    		Vendor vendor = getVendorWithAllFields(service);
			Vendor vendorOut = service.add(vendor);

			ReferenceType vendorRef = getVendorRef(vendorOut);

			//add bill
			Bill bill = getBillFields(service);
			bill.setVendorRef(vendorRef);
			Bill billOut = service.add(bill);

    		//make bill payment
			BillPayment billPayment = getBillPaymentFields(service, billOut);
			billPayment.setVendorRef(vendorRef);
			BillPayment billPaymentOut = service.add(billPayment);

    		//add vendor credit
			VendorCredit vendorCredit = getVendorCreditFields(service);
			vendorCredit.setVendorRef(vendorRef);
			VendorCredit vendorCreditOut = service.add(vendorCredit);
    		
    		//return response back
    		return processResponse(vendorCreditOut);
			
		}
	        
        catch (InvalidTokenException e) {			
			return new JSONObject().put("response","InvalidToken - Refreshtoken and try again").toString();
		} catch (FMSException e) {
			List<Error> list = e.getErrorList();
			list.forEach(error -> logger.error("Error while calling the API :: " + error.getMessage()));
			return new JSONObject().put("response","Failed").toString();
		}
    }

	private String processResponse(Object entity) {
		ObjectMapper mapper = new ObjectMapper();
		try {
			String jsonInString = mapper.writeValueAsString(entity);
			return jsonInString;
		} catch (JsonProcessingException e) {
			logger.error("Exception while getting company info ", e);
			return new JSONObject().put("response","Failed").toString();
		}
	}

	private ReferenceType getVendorRef(Vendor vendor) {
		ReferenceType vendorRef = new ReferenceType();
		vendorRef.setName(vendor.getDisplayName());
		vendorRef.setValue(vendor.getId());
		return vendorRef;
	}


	public Vendor getVendorWithAllFields(DataService service) throws FMSException, ParseException {
		Vendor vendor = new Vendor();
		// Mandatory Fields
		vendor.setDisplayName(RandomStringUtils.randomAlphanumeric(8));

		// Optional Fields
		vendor.setCompanyName("ABC Corp");
		vendor.setTitle(RandomStringUtils.randomAlphanumeric(7));
		vendor.setGivenName(RandomStringUtils.randomAlphanumeric(8));
		vendor.setMiddleName(RandomStringUtils.randomAlphanumeric(1));
		vendor.setFamilyName(RandomStringUtils.randomAlphanumeric(8));
		vendor.setSuffix("Sr.");
		vendor.setPrintOnCheckName("MS");

		vendor.setBillAddr(getPhysicalAddress());

		vendor.setTaxIdentifier("1111111");

		vendor.setPrimaryEmailAddr(getEmailAddress());

		vendor.setPrimaryPhone(getPrimaryPhone());
		vendor.setAlternatePhone(getAlternatePhone());
		vendor.setMobile(getMobilePhone());
		vendor.setFax(getFax());

		vendor.setWebAddr(getWebSiteAddress());

		vendor.setDomain("QBO");

		Term term = getTerm(service);

		vendor.setTermRef(getTermRef(term));

		vendor.setAcctNum("11223344");
		vendor.setBalance(new BigDecimal("0"));
		try {
			vendor.setOpenBalanceDate(DateUtils.getCurrentDateTime());
		} catch (ParseException e) {
			throw new FMSException("ParseException while getting current date.");
		}

		return vendor;
	}

	private PhysicalAddress getPhysicalAddress(){
			PhysicalAddress billingAdd = new PhysicalAddress();
			billingAdd.setLine1("123 Main St");
			billingAdd.setCity("Mountain View");
			billingAdd.setCountry("United States");
			billingAdd.setCountrySubDivisionCode("CA");
			billingAdd.setPostalCode("94043");
			return billingAdd;
	}

	private EmailAddress getEmailAddress() {
		EmailAddress emailAddr = new EmailAddress();
		emailAddr.setAddress("test@abc.com");
		return emailAddr;
	}

	private TelephoneNumber getPrimaryPhone() {
		TelephoneNumber primaryNum = new TelephoneNumber();
		primaryNum.setFreeFormNumber("(650)111-1111");
		primaryNum.setDefault(true);
		primaryNum.setTag("Business");
		return primaryNum;
	}

	private TelephoneNumber getAlternatePhone() {
		TelephoneNumber alternativeNum = new TelephoneNumber();
		alternativeNum.setFreeFormNumber("(650)111-2222");
		alternativeNum.setDefault(false);
		alternativeNum.setTag("Business");
		return alternativeNum;
	}

	private TelephoneNumber getMobilePhone() {
		TelephoneNumber mobile = new TelephoneNumber();
		mobile.setFreeFormNumber("(650)111-3333");
		mobile.setDefault(false);
		mobile.setTag("Home");
		return mobile;
	}

	private TelephoneNumber getFax() {
		TelephoneNumber fax = new TelephoneNumber();
		fax.setFreeFormNumber("(650)111-1111");
		fax.setDefault(false);
		fax.setTag("Business");
		return fax;
	}

	private WebSiteAddress getWebSiteAddress() {
		WebSiteAddress webSite = new WebSiteAddress();
		webSite.setURI("http://abccorp.com");
		webSite.setDefault(true);
		webSite.setTag("Business");
		return webSite;
	}

	public Term getTerm(DataService service) throws FMSException {
		List<Term> terms = (List<Term>) service.findAll(new Term());
		if (!terms.isEmpty()) {
			return terms.get(0);
		}
		return createTerm(service);
	}

	public Term getTermFields() throws FMSException {

		Term term = new Term();
		term.setName("Term_" + RandomStringUtils.randomAlphanumeric(5));
		term.setActive(true);
		term.setType("STANDARD");
		term.setDiscountPercent(new BigDecimal("50.00"));
		term.setDueDays(50);
		return term;
	}

	private Term createTerm(DataService service) throws FMSException {
		return service.add(getTermFields());
	}

	public  ReferenceType getTermRef(Term term) {
		ReferenceType termRef = new ReferenceType();
		termRef.setName(term.getName());
		termRef.setValue(term.getId());
		return termRef;
	}

	private  Bill getBillFields(DataService service) throws FMSException, ParseException {

		Bill bill = new Bill();

		//Vendor vendor = VendorHelper.getVendor(service);
		//bill.setVendorRef(VendorHelper.getVendorRef(vendor));

		Account liabilityAccount = getLiabilityBankAccount(service);
		bill.setAPAccountRef(getAccountRef(liabilityAccount));

		Line line1 = new Line();
		line1.setAmount(new BigDecimal("30.00"));
		line1.setDetailType(LineDetailTypeEnum.ACCOUNT_BASED_EXPENSE_LINE_DETAIL);
		AccountBasedExpenseLineDetail detail = new AccountBasedExpenseLineDetail();
		Account account = getExpenseBankAccount(service);
		ReferenceType expenseAccountRef = getAccountRef(account);
		detail.setAccountRef(expenseAccountRef);
		line1.setAccountBasedExpenseLineDetail(detail);

		List<Line> lines1 = new ArrayList<Line>();
		lines1.add(line1);
		bill.setLine(lines1);

		bill.setBillEmail(getEmailAddress());
		bill.setDomain("QBO");

		bill.setGlobalTaxCalculation(GlobalTaxCalculationEnum.NOT_APPLICABLE);

		bill.setRemitToAddr(getPhysicalAddress());

		bill.setReplyEmail(getEmailAddress());

		bill.setShipAddr(getPhysicalAddress());

		bill.setTotalAmt(new BigDecimal("30.00"));
		bill.setTxnDate(DateUtils.getCurrentDateTime());
		bill.setDueDate(DateUtils.getDateWithNextDays(45));

		return bill;
	}

	private  ReferenceType getAccountRef(Account account) {
		ReferenceType accountRef = new ReferenceType();
		accountRef.setName(account.getName());
		accountRef.setValue(account.getId());
		return accountRef;
	}

	private  Account getExpenseBankAccount(DataService service) throws FMSException {
		List<Account> accounts = (List<Account>) service.findAll(new Account());
		if (!accounts.isEmpty()) {
			Iterator<Account> itr = accounts.iterator();
			while (itr.hasNext()) {
				Account account = itr.next();
				if (account.getAccountType().equals(AccountTypeEnum.EXPENSE)) {
					return account;
				}
			}
		}
		return createExpenseBankAccount(service);
	}

	private Account createExpenseBankAccount(DataService service) throws FMSException {
		return service.add(getExpenseBankAccountFields());
	}



	private  Account getExpenseBankAccountFields() throws FMSException {
		Account account = new Account();
		account.setName("Expense" + RandomStringUtils.randomAlphabetic(5));
		account.setSubAccount(false);
		account.setFullyQualifiedName(account.getName());
		account.setActive(true);
		account.setClassification(AccountClassificationEnum.EXPENSE);
		account.setAccountType(AccountTypeEnum.EXPENSE);
		account.setAccountSubType(AccountSubTypeEnum.ADVERTISING_PROMOTIONAL.value());
		account.setCurrentBalance(new BigDecimal("0"));
		account.setCurrentBalanceWithSubAccounts(new BigDecimal("0"));
		ReferenceType currencyRef = new ReferenceType();
		currencyRef.setName("United States Dollar");
		currencyRef.setValue("USD");
		account.setCurrencyRef(currencyRef);

		return account;
	}


	private Account getLiabilityBankAccount(DataService service) throws FMSException {
		List<Account> accounts = (List<Account>) service.findAll(new Account());
		if (!accounts.isEmpty()) {
			Iterator<Account> itr = accounts.iterator();
			while (itr.hasNext()) {
				Account account = itr.next();
				if (account.getAccountType().equals(AccountTypeEnum.ACCOUNTS_PAYABLE)
						&& account.getClassification().equals(AccountClassificationEnum.LIABILITY)) {
					return account;
				}
			}
		}
		return createLiabilityBankAccount(service);
	}

	private Account createLiabilityBankAccount(DataService service) throws FMSException {
		return service.add(getLiabilityBankAccountFields());
	}

	private Account getLiabilityBankAccountFields() throws FMSException {
		Account account = new Account();
		account.setName("Equity" + RandomStringUtils.randomAlphabetic(5));
		account.setSubAccount(false);
		account.setFullyQualifiedName(account.getName());
		account.setActive(true);
		account.setClassification(AccountClassificationEnum.LIABILITY);
		account.setAccountType(AccountTypeEnum.ACCOUNTS_PAYABLE);
		account.setAccountSubType(AccountSubTypeEnum.ACCOUNTS_PAYABLE.value());
		account.setCurrentBalance(new BigDecimal("3000"));
		account.setCurrentBalanceWithSubAccounts(new BigDecimal("3000"));
		ReferenceType currencyRef = new ReferenceType();
		currencyRef.setName("United States Dollar");
		currencyRef.setValue("USD");
		account.setCurrencyRef(currencyRef);

		return account;
	}

	private  VendorCredit getVendorCreditFields(DataService service) throws FMSException, ParseException {

		VendorCredit vendorCredit = new VendorCredit();

		Account account = getLiabilityBankAccount(service);
		vendorCredit.setAPAccountRef(getAccountRef(account));

		Line line1 = new Line();
		line1.setAmount(new BigDecimal("30.00"));
		line1.setDetailType(LineDetailTypeEnum.ACCOUNT_BASED_EXPENSE_LINE_DETAIL);
		AccountBasedExpenseLineDetail detail = new AccountBasedExpenseLineDetail();
		Account expenseAccount = getExpenseBankAccount(service);
		detail.setAccountRef(getAccountRef(expenseAccount));
		line1.setAccountBasedExpenseLineDetail(detail);

		List<Line> lines1 = new ArrayList<Line>();
		lines1.add(line1);
		vendorCredit.setLine(lines1);

		vendorCredit.setDomain("QBO");
		vendorCredit.setPrivateNote("Credit should be specified");
		vendorCredit.setTxnDate(DateUtils.getCurrentDateTime());
		vendorCredit.setTotalAmt(new BigDecimal("30.00"));
		return vendorCredit;
	}


	private BillPayment getBillPaymentFields(DataService service, Bill bill) throws FMSException, ParseException {
		BillPayment billPayment = new BillPayment();

		billPayment.setTxnDate(DateUtils.getCurrentDateTime());

		billPayment.setPrivateNote("Check billPayment");

		//Vendor vendor = VendorHelper.getVendor(service);
		//billPayment.setVendorRef(VendorHelper.getVendorRef(vendor));

		Line line1 = new Line();
		line1.setAmount(new BigDecimal("30"));
		List<LinkedTxn> linkedTxnList1 = new ArrayList<LinkedTxn>();
		LinkedTxn linkedTxn1 = new LinkedTxn();
		//Bill bill = getBill(service);
		linkedTxn1.setTxnId(bill.getId());
		linkedTxn1.setTxnType(TxnTypeEnum.BILL.value());
		linkedTxnList1.add(linkedTxn1);
		line1.setLinkedTxn(linkedTxnList1);

		List<Line> lineList = new ArrayList<Line>();
		lineList.add(line1);
		billPayment.setLine(lineList);

		BillPaymentCheck billPaymentCheck = new BillPaymentCheck();
		Account bankAccount = getCheckBankAccount(service);
		billPaymentCheck.setBankAccountRef(getAccountRef(bankAccount));

		billPaymentCheck.setCheckDetail(getCheckPayment());

		billPaymentCheck.setPayeeAddr(getPhysicalAddress());
		billPaymentCheck.setPrintStatus(PrintStatusEnum.NEED_TO_PRINT);

		billPayment.setCheckPayment(billPaymentCheck);
		billPayment.setPayType(BillPaymentTypeEnum.CHECK);
		billPayment.setTotalAmt(new BigDecimal("30"));
		return billPayment;
	}

	private  Account getCheckBankAccount(DataService service) throws FMSException, ParseException {
		List<Account> accounts = (List<Account>) service.findAll(new Account());
		if (!accounts.isEmpty()) {
			Iterator<Account> itr = accounts.iterator();
			while (itr.hasNext()) {
				Account account = itr.next();
				if (account.getAccountType().equals(AccountTypeEnum.BANK)) {
					return account;
				}
			}
		}
		return createBankAccount(service);
	}

	private  Account createBankAccount(DataService service) throws FMSException, ParseException {
		return service.add(getBankAccountFields());
	}

	private Account getBankAccountFields() throws FMSException {
		Account account = new Account();
		account.setName("Ba" + RandomStringUtils.randomAlphanumeric(7));
		account.setSubAccount(false);
		account.setFullyQualifiedName(account.getName());
		account.setActive(true);
		account.setClassification(AccountClassificationEnum.ASSET);
		account.setAccountType(AccountTypeEnum.BANK);
		account.setCurrentBalance(new BigDecimal("0"));
		account.setCurrentBalanceWithSubAccounts(new BigDecimal("0"));
		account.setTxnLocationType("FranceOverseas");
		account.setAcctNum("B" + RandomStringUtils.randomAlphanumeric(6));

		return account;
	}

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
}

