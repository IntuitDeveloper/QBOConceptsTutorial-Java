package com.intuit.developer.tutorials.controller;

import java.util.List;

import javax.servlet.http.HttpSession;

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
import com.intuit.ipp.data.Error;
import com.intuit.ipp.data.Report;
import com.intuit.ipp.exception.FMSException;
import com.intuit.ipp.exception.InvalidTokenException;
import com.intuit.ipp.services.ReportName;
import com.intuit.ipp.services.ReportService;

/**
 * @author dderose
 *
 */
@Controller
public class ReportsController {

	@Autowired
	OAuth2PlatformClientFactory factory;

	@Autowired
    public QBOServiceHelper helper;

	private static final Logger logger = Logger.getLogger(ReportsController.class);


	/**
     	* Sample QBO API call using OAuth2 tokens
     	*
     	* @param session the HttpSession
     	* @return a report in JSON String format
     	*/
	@ResponseBody
    	@RequestMapping("/reports")
    	public String callReportsConcept(HttpSession session) {
    		String realmId = (String)session.getAttribute("realmId");

    		if (StringUtils.isEmpty(realmId)) {
    			return new JSONObject().put("response","No realm ID.  QBO calls only work if the accounting scope was passed!").toString();
    		}

    		String accessToken = (String)session.getAttribute("access_token");
        	try {
			
        	// get ReportService
        	ReportService service = helper.getReportService(realmId, accessToken);
        	
        	/*
             * Read default profit and Loss report: 
             * Default date used: This fiscal year to date
             * Deafult accounting method: Defined in Preferences.ReportPrefs.ReportBasis. The two accepted values are "Accural" and "Cash"
             * Default includes data for all customers
             * */
            Report defaultPnLReport = service.executeReport(ReportName.PROFITANDLOSS.toString());
            
            /*
             * Read default Balance sheet report:
             * Default date used: This fiscal year to date
             * Deafult accounting method: Defined in Preferences.ReportPrefs.ReportBasis. The two accepted values are "Accural" and "Cash"
             * Default includes data for all customers
             * Default it is summarized by Total
             * */  
            Report defaultBalanceSheet = service.executeReport(ReportName.BALANCESHEET.toString());
            
            /*  report for given start and end date
             *  set start_date and end_date properties in the ReportService instance with the date range in yyyy-mm-dd format
             * */
            
            service.setStart_date("2018-01-01");
            service.setEnd_date("2018-04-15");
           
            //Run BalanceSheet yearly report
            defaultBalanceSheet = service.executeReport(ReportName.BALANCESHEET.toString());
            
            //Run P&L yearly report
            defaultPnLReport = service.executeReport(ReportName.PROFITANDLOSS.toString());

           /* Year End Balance Sheet report summarized by Customer
            * set the customer property to the customer.Id and set summarize_column_by property to "Customers"
            * You can also set customer property with multiple customer ids comma seperated.
            * You can summarize by the following:Total, Customers, Vendors, Classes, Departments, Employees, ProductsAndServices by setting the summarize_column_by property
            * */
            service.setSummarize_column_by("Customers");
           
            //Run BalanceSheet report summarized by column
            defaultBalanceSheet = service.executeReport(ReportName.BALANCESHEET.toString());
            logger.info("ReportName -> name: " + defaultBalanceSheet.getHeader().getReportName().toLowerCase());
           
            //Run P&L yearly report summarized by column
            defaultPnLReport = service.executeReport(ReportName.PROFITANDLOSS.toString());
            logger.info("ReportName -> name: " + defaultPnLReport.getHeader().getReportName().toLowerCase());

            //return P&L response
            return createResponse(defaultPnLReport);
          
		} catch (InvalidTokenException e) {
        		logger.error("invalid token: ", e);
			return new JSONObject().put("response","InvalidToken - Refreshtoken and try again").toString();
		} catch (FMSException e) {
			List<Error> list = e.getErrorList();
			list.forEach(error -> logger.error("Error while calling the API :: " + error.getMessage()));
			return new JSONObject().put("response","Failed").toString();
		}

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
