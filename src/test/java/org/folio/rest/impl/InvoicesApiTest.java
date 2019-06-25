package org.folio.rest.impl;

import static java.util.stream.Collectors.groupingBy;
import static javax.ws.rs.core.MediaType.APPLICATION_JSON;
import static javax.ws.rs.core.MediaType.TEXT_PLAIN;
import static org.folio.invoices.utils.ErrorCodes.FUNDS_NOT_FOUND;
import static org.folio.invoices.utils.ErrorCodes.GENERIC_ERROR_CODE;
import static org.folio.invoices.utils.ErrorCodes.INVOICE_TOTAL_REQUIRED;
import static org.folio.invoices.utils.ErrorCodes.MOD_CONFIG_ERROR;
import static org.folio.invoices.utils.ErrorCodes.PO_LINE_NOT_FOUND;
import static org.folio.invoices.utils.ErrorCodes.PO_LINE_UPDATE_FAILURE;
import static org.folio.invoices.utils.ErrorCodes.PROHIBITED_FIELD_CHANGING;
import static org.folio.invoices.utils.ErrorCodes.VOUCHER_NOT_FOUND;
import static org.folio.invoices.utils.ErrorCodes.VOUCHER_UPDATE_FAILURE;
import static org.folio.invoices.utils.HelperUtils.calculateInvoiceLineTotals;
import static org.folio.invoices.utils.HelperUtils.calculateVoucherAmount;
import static org.folio.invoices.utils.HelperUtils.calculateVoucherLineAmount;
import static org.folio.invoices.utils.ResourcePathResolver.FOLIO_INVOICE_NUMBER;
import static org.folio.invoices.utils.ResourcePathResolver.FUNDS;
import static org.folio.invoices.utils.ResourcePathResolver.INVOICES;
import static org.folio.invoices.utils.ResourcePathResolver.INVOICE_LINES;
import static org.folio.invoices.utils.ResourcePathResolver.ORDER_LINES;
import static org.folio.invoices.utils.ResourcePathResolver.VOUCHERS;
import static org.folio.invoices.utils.ResourcePathResolver.VOUCHER_LINES;
import static org.folio.rest.impl.InvoiceHelper.MAX_IDS_FOR_GET_RQ;
import static org.folio.rest.impl.InvoiceHelper.NO_INVOICE_LINES_ERROR_MSG;
import static org.folio.rest.impl.InvoiceLinesApiTest.INVOICE_LINES_LIST_PATH;
import static org.folio.rest.impl.InvoiceLinesApiTest.INVOICE_LINE_SAMPLE_PATH;
import static org.folio.rest.impl.InvoicesImpl.PROTECTED_AND_MODIFIED_FIELDS;
import static org.folio.rest.impl.MockServer.ERROR_CONFIG_X_OKAPI_TENANT;
import static org.folio.rest.impl.MockServer.ERROR_X_OKAPI_TENANT;
import static org.folio.rest.impl.MockServer.INVOICE_NUMBER_ERROR_X_OKAPI_TENANT;
import static org.folio.rest.impl.MockServer.NON_EXIST_CONFIG_X_OKAPI_TENANT;
import static org.folio.rest.impl.MockServer.addMockEntry;
import static org.folio.rest.impl.MockServer.getRqRsEntries;
import static org.folio.rest.impl.MockServer.serverRqRs;
import static org.folio.rest.impl.VoucherHelper.DEFAULT_SYSTEM_CURRENCY;
import static org.folio.rest.impl.VouchersApiTest.VOUCHERS_LIST_PATH;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.arrayContaining;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsEqual.equalTo;
import static org.hamcrest.core.IsNot.not;
import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.money.convert.MonetaryConversions;

import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.http.HttpStatus;
import org.folio.invoices.utils.InvoiceProtectedFields;
import org.folio.rest.acq.model.VoucherLineCollection;
import org.folio.rest.acq.model.finance.Fund;
import org.folio.rest.acq.model.finance.FundCollection;
import org.folio.rest.acq.model.orders.CompositePoLine;
import org.folio.rest.jaxrs.model.Adjustment;
import org.folio.rest.jaxrs.model.Error;
import org.folio.rest.jaxrs.model.Errors;
import org.folio.rest.jaxrs.model.Invoice;
import org.folio.rest.jaxrs.model.InvoiceCollection;
import org.folio.rest.jaxrs.model.InvoiceLine;
import org.folio.rest.jaxrs.model.InvoiceLineCollection;
import org.folio.rest.jaxrs.model.Voucher;
import org.folio.rest.jaxrs.model.VoucherCollection;
import org.folio.rest.jaxrs.model.VoucherLine;
import org.hamcrest.Matchers;
import org.junit.Test;

import io.restassured.http.Headers;
import io.restassured.response.Response;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;

public class InvoicesApiTest extends ApiTestBase {

  private static final Logger logger = LoggerFactory.getLogger(InvoicesApiTest.class);

  private static final String INVOICE_PATH = "/invoice/invoices";
	private static final String INVOICE_ID_PATH = INVOICE_PATH+ "/%s";
  private static final String INVOICE_ID_WITH_LANG_PATH = INVOICE_ID_PATH + "?lang=%s";
  private static final String INVOICE_PATH_BAD = "/invoice/bad";
  private static final String INVOICE_NUMBER_PATH = "/invoice/invoice-number";
  static final String INVOICE_MOCK_DATA_PATH = BASE_MOCK_DATA_PATH + "invoices/";
  private static final String PO_LINE_MOCK_DATA_PATH = BASE_MOCK_DATA_PATH + "poLines/";
  static final String APPROVED_INVOICE_ID = "c0d08448-347b-418a-8c2f-5fb50248d67e";
  private static final String REVIEWED_INVOICE_ID = "3773625a-dc0d-4a2d-959e-4a91ee265d67";
  private static final String APPROVED_INVOICE_SAMPLE_PATH = INVOICE_MOCK_DATA_PATH + APPROVED_INVOICE_ID + ".json";
  private static final String REVIEWED_INVOICE_SAMPLE_PATH = INVOICE_MOCK_DATA_PATH + REVIEWED_INVOICE_ID + ".json";
  private static final String REVIEWED_INVOICE_WITH_EXISTING_VOUCHER_SAMPLE_PATH = INVOICE_MOCK_DATA_PATH + "402d0d32-7377-46a7-86ab-542b5684506e.json";
  private static final String OPEN_INVOICE_SAMPLE_PATH = INVOICE_MOCK_DATA_PATH + "52fd6ec7-ddc3-4c53-bc26-2779afc27136.json";

  static final String BAD_QUERY = "unprocessableQuery";
  private static final String VENDOR_INVOICE_NUMBER_FIELD = "vendorInvoiceNo";
  static final String EXISTING_VENDOR_INV_NO = "existingVendorInvoiceNo";
  private static final String BAD_INVOICE_ID = "5a34ae0e-5a11-4337-be95-1a20cfdc3161";
  private static final String EXISTENT_PO_LINE_ID = "c2755a78-2f8d-47d0-a218-059a9b7391b4";
  private static final String EXISTING_VOUCHER_ID = "a9b99f8a-7100-47f2-9903-6293d44a9905";
  private static final String STATUS = "status";
  private static final String INVALID_CURRENCY = "ABC";


  @Test
  public void testGetInvoicingInvoices() {
    logger.info("=== Test Get Invoices by without query - get 200 by successful retrieval of invoices ===");

    final InvoiceCollection resp = verifySuccessGet(INVOICE_PATH, InvoiceCollection.class);

    assertEquals(3, resp.getTotalRecords().intValue());
  }

  @Test
  public void testGetInvoicingInvoicesWithQueryParam() {
    logger.info("=== Test Get Invoices with query - get 200 by successful retrieval of invoices by query ===");

    String endpointQuery = String.format("%s?query=%s==%s", INVOICE_PATH,  VENDOR_INVOICE_NUMBER_FIELD, EXISTING_VENDOR_INV_NO);

    final InvoiceCollection resp = verifySuccessGet(endpointQuery, InvoiceCollection.class);

    assertEquals(1, resp.getTotalRecords().intValue());
  }

  @Test
  public void testGetInvoicesBadQuery() {
    logger.info("=== Test Get Invoices by query - unprocessable query to emulate 400 from storage ===");

    String endpointQuery = String.format("%s?query=%s", INVOICE_PATH,  BAD_QUERY);

    verifyGet(endpointQuery, APPLICATION_JSON, 400);
  }

  @Test
  public void testGetInvoicesInternalServerError() {
    logger.info("=== Test Get Invoices by query - emulating 500 from storage ===");

    String endpointQuery = String.format("%s?query=%s", INVOICE_PATH,  ID_FOR_INTERNAL_SERVER_ERROR);

    verifyGet(endpointQuery, APPLICATION_JSON, 500);

  }

  @Test
  public void testGetInvoicingInvoicesBadRequestUrl() {
    logger.info("=== Test Get Invoices by query - emulating 400 by sending bad request Url ===");

    verifyGet(INVOICE_PATH_BAD, TEXT_PLAIN, 400);
  }

  @Test
  public void testGetInvoicingInvoicesById() {
    logger.info("=== Test Get Invoice By Id ===");

    final Invoice resp = verifySuccessGet(String.format(INVOICE_ID_PATH, APPROVED_INVOICE_ID), Invoice.class);

    logger.info(JsonObject.mapFrom(resp).encodePrettily());
    assertThat(resp.getId(), equalTo(APPROVED_INVOICE_ID));

    /* The invoice has 2 not prorated adjustments, 3 related invoice lines and each one has adjustment */
    assertThat(resp.getAdjustmentsTotal(), equalTo(7.17d));
    assertThat(resp.getSubTotal(), equalTo(10.6d));
    assertThat(resp.getTotal(), equalTo(17.77d));

    // Verify that expected number of external calls made
    assertThat(getRqRsEntries(HttpMethod.GET, INVOICES), hasSize(1));
    assertThat(getRqRsEntries(HttpMethod.GET, INVOICE_LINES), hasSize(1));
  }

  @Test
  public void testGetInvoiceWithoutLines() throws IOException {
    logger.info("=== Test Get Invoice without associated invoice lines ===");

    // ===  Preparing invoice for test with random id to make sure no lines exists  ===
    String id = UUID.randomUUID().toString();
    Invoice invoice = new JsonObject(getMockData(APPROVED_INVOICE_SAMPLE_PATH)).mapTo(Invoice.class);
    invoice.setId(id);
    invoice.setLockTotal(true);
    invoice.setTotal(15d);

    addMockEntry(INVOICES, JsonObject.mapFrom(invoice));

    // ===  Run test  ===
    final Invoice resp = verifySuccessGet(String.format(INVOICE_ID_PATH, id), Invoice.class);

    logger.info(JsonObject.mapFrom(resp).encodePrettily());
    assertThat(resp.getId(), equalTo(id));

    /* The invoice has 2 not prorated adjustments one with fixed amount and another with percentage type */
    assertThat(resp.getAdjustmentsTotal(), equalTo(5.06d));
    assertThat(resp.getSubTotal(), equalTo(0d));
    assertThat(resp.getTotal(), equalTo(15d));

    // Verify that expected number of external calls made
    assertThat(getRqRsEntries(HttpMethod.GET, INVOICES), hasSize(1));
    assertThat(getRqRsEntries(HttpMethod.GET, INVOICE_LINES), hasSize(1));
  }

  @Test
  public void testGetInvoiceWithoutLinesButProratedAdjustments() throws IOException {
    logger.info("=== Test Get Invoice without associated invoice lines ===");

    // ===  Preparing invoice for test with random id to make sure no lines exists  ===
    String id = UUID.randomUUID().toString();
    Invoice invoice = new JsonObject(getMockData(APPROVED_INVOICE_SAMPLE_PATH)).mapTo(Invoice.class);
    invoice.setId(id);
    invoice.getAdjustments().forEach(adj -> adj.setProrate(Adjustment.Prorate.BY_LINE));
    // Setting totals to verify that they are re-calculated
    invoice.setAdjustmentsTotal(5d);
    invoice.setSubTotal(10d);
    invoice.setTotal(15d);
    invoice.setLockTotal(false);

    addMockEntry(INVOICES, JsonObject.mapFrom(invoice));

    // ===  Run test  ===
    final Invoice resp = verifySuccessGet(String.format(INVOICE_ID_PATH, id), Invoice.class);

    logger.info(JsonObject.mapFrom(resp).encodePrettily());
    assertThat(resp.getId(), equalTo(id));

    /* The invoice has 2 not prorated adjustments one with fixed amount and another with percentage type */
    assertThat(resp.getAdjustmentsTotal(), equalTo(0d));
    assertThat(resp.getSubTotal(), equalTo(0d));
    assertThat(resp.getTotal(), equalTo(0d));

    // Verify that expected number of external calls made
    assertThat(getRqRsEntries(HttpMethod.GET, INVOICES), hasSize(1));
    assertThat(getRqRsEntries(HttpMethod.GET, INVOICE_LINES), hasSize(1));
  }

  @Test
  public void testGetInvoicingInvoicesByIdNotFound() {
    logger.info("=== Test Get Invoices by Id - 404 Not found ===");

    final Response resp = verifyGet(String.format(INVOICE_ID_PATH, BAD_INVOICE_ID), APPLICATION_JSON, 404);

    String actual = resp.getBody().as(Errors.class).getErrors().get(0).getMessage();
    logger.info("Id not found: " + actual);

    assertEquals(BAD_INVOICE_ID, actual);
  }

  @Test
  public void testUpdateValidInvoice() {
    logger.info("=== Test update invoice by id ===");

     String newInvoiceNumber = "testFolioInvoiceNumber";

  	Invoice reqData = getMockAsJson(OPEN_INVOICE_SAMPLE_PATH).mapTo(Invoice.class);
  	reqData.setFolioInvoiceNo(newInvoiceNumber);

    String id = reqData.getId();
  	String jsonBody = JsonObject.mapFrom(reqData).encode();

  	verifyPut(String.format(INVOICE_ID_PATH, id), jsonBody, "", 204);
  	assertThat(serverRqRs.get(INVOICES, HttpMethod.PUT).get(0).getString(FOLIO_INVOICE_NUMBER), not(newInvoiceNumber));
  }

  @Test
  public void testTransitionFromOpenToApproved() {
    logger.info("=== Test transition invoice to Approved ===");

    List<InvoiceLine> invoiceLines = getMockAsJson(INVOICE_LINES_LIST_PATH).mapTo(InvoiceLineCollection.class).getInvoiceLines();
    Invoice reqData = getMockAsJson(OPEN_INVOICE_SAMPLE_PATH).mapTo(Invoice.class);
    String id = reqData.getId();
    invoiceLines
      .forEach(invoiceLine -> {
        invoiceLine.setInvoiceId(id);
        addMockEntry(INVOICE_LINES, JsonObject.mapFrom(invoiceLine));
      });

    reqData.setStatus(Invoice.Status.APPROVED);

    String jsonBody = JsonObject.mapFrom(reqData).encode();
    Headers headers = prepareHeaders(X_OKAPI_URL, X_OKAPI_TENANT, X_OKAPI_TOKEN);
    verifyPut(String.format(INVOICE_ID_PATH, id), jsonBody, headers, "", 204);

    List<JsonObject> vouchersCreated = serverRqRs.get(VOUCHERS, HttpMethod.POST);
    assertThat(vouchersCreated, notNullValue());
    assertThat(vouchersCreated, hasSize(1));
    Voucher voucherCreated = vouchersCreated.get(0).mapTo(Voucher.class);
    assertThat(voucherCreated.getSystemCurrency(), equalTo("GBP"));
    verifyTransitionToApproved(voucherCreated, invoiceLines);
  }

  @Test
  public void testTransitionToApprovedWithDefaultSystemCurrency() {
    logger.info("=== Test transition invoice to Approved with empty config ===");
    List<InvoiceLine> invoiceLines = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      invoiceLines.add(getMockAsJson(INVOICE_LINE_SAMPLE_PATH).mapTo(InvoiceLine.class));
    }

    Invoice reqData = getMockAsJson(REVIEWED_INVOICE_SAMPLE_PATH).mapTo(Invoice.class);
    invoiceLines
      .forEach(invoiceLine -> {
        invoiceLine.setId(UUID.randomUUID().toString());
        invoiceLine.setInvoiceId(reqData.getId());
        addMockEntry(INVOICE_LINES, JsonObject.mapFrom(invoiceLine));
      });

    reqData.setStatus(Invoice.Status.APPROVED);

    String id = reqData.getId();
    String jsonBody = JsonObject.mapFrom(reqData).encode();
    Headers headers = prepareHeaders(X_OKAPI_URL, NON_EXIST_CONFIG_X_OKAPI_TENANT, X_OKAPI_TOKEN);
    verifyPut(String.format(INVOICE_ID_PATH, id), jsonBody, headers, "", 204);

    List<JsonObject> vouchersCreated = serverRqRs.get(VOUCHERS, HttpMethod.POST);
    assertThat(vouchersCreated, notNullValue());
    assertThat(vouchersCreated, hasSize(1));
    Voucher voucherCreated = vouchersCreated.get(0).mapTo(Voucher.class);
    assertThat(voucherCreated.getSystemCurrency(), equalTo(DEFAULT_SYSTEM_CURRENCY));
    verifyTransitionToApproved(voucherCreated, invoiceLines);
  }

  @Test
  public void testTransitionToApprovedWithExistingVoucherAndVoucherLines() {
    logger.info("=== Test transition invoice to Approved with existing voucher and voucherLines ===");

    InvoiceLine invoiceLine = getMockAsJson(INVOICE_LINE_SAMPLE_PATH).mapTo(InvoiceLine.class);


    Invoice reqData = getMockAsJson(REVIEWED_INVOICE_WITH_EXISTING_VOUCHER_SAMPLE_PATH).mapTo(Invoice.class);
    invoiceLine.setId(UUID.randomUUID().toString());
    invoiceLine.setInvoiceId(reqData.getId());
    prepareMockVoucher(reqData.getId());
    VoucherLine voucherLine = new VoucherLine().withVoucherId(EXISTING_VOUCHER_ID);
    addMockEntry(INVOICE_LINES, JsonObject.mapFrom(invoiceLine));
    addMockEntry(VOUCHER_LINES, JsonObject.mapFrom(voucherLine));

    reqData.setStatus(Invoice.Status.APPROVED);

    String id = reqData.getId();
    String jsonBody = JsonObject.mapFrom(reqData).encode();

    verifyPut(String.format(INVOICE_ID_PATH, id), jsonBody, "", 204);
    List<JsonObject> vouchersUpdated = serverRqRs.get(VOUCHERS, HttpMethod.PUT);
    List<JsonObject> voucherLinesDeletions = serverRqRs.get(VOUCHER_LINES, HttpMethod.DELETE);
    List<JsonObject> voucherLinesSearches = serverRqRs.get(VOUCHER_LINES, HttpMethod.GET);

    assertThat(vouchersUpdated, notNullValue());
    assertThat(vouchersUpdated, hasSize(1));
    assertThat(voucherLinesDeletions, notNullValue());
    assertThat(voucherLinesSearches, notNullValue());
    assertThat(voucherLinesSearches, hasSize(1));
    VoucherLineCollection voucherLineCollection = voucherLinesSearches.get(0).mapTo(VoucherLineCollection.class);
    assertThat(voucherLinesDeletions, hasSize(voucherLineCollection.getTotalRecords()));


    Voucher updatedVoucher = vouchersUpdated.get(0).mapTo(Voucher.class);

    verifyTransitionToApproved(updatedVoucher, Collections.singletonList(invoiceLine));
  }

  @Test
  public void testTransitionToApprovedInvalidCurrency() {
    logger.info("=== Test transition invoice to Approved with invalid invoiceCurrency ===");

    InvoiceLine invoiceLine = getMockAsJson(INVOICE_LINE_SAMPLE_PATH).mapTo(InvoiceLine.class);


    Invoice reqData = getMockAsJson(REVIEWED_INVOICE_WITH_EXISTING_VOUCHER_SAMPLE_PATH).mapTo(Invoice.class);
    invoiceLine.setId(UUID.randomUUID().toString());
    invoiceLine.setInvoiceId(reqData.getId());
    addMockEntry(INVOICE_LINES, JsonObject.mapFrom(invoiceLine));


    reqData.setStatus(Invoice.Status.APPROVED);
    reqData.setCurrency(INVALID_CURRENCY);

    String id = reqData.getId();
    String jsonBody = JsonObject.mapFrom(reqData).encode();
    Errors errors = verifyPut(String.format(INVOICE_ID_PATH, id), jsonBody, APPLICATION_JSON, 500)
      .then()
        .extract()
          .body().as(Errors.class);

    assertThat(errors, notNullValue());
    assertThat(errors.getErrors(), hasSize(1));
    assertThat(errors.getErrors().get(0).getAdditionalProperties().get("cause"), equalTo("Unknown currency code: " + INVALID_CURRENCY));
  }

  @Test
  public void testTransitionToApprovedErrorFromModConfig() {
    logger.info("=== Test transition invoice to Approved with mod-config error ===");

    Errors errors = transitionToApprovedWithError(REVIEWED_INVOICE_SAMPLE_PATH, prepareHeaders(X_OKAPI_URL, ERROR_CONFIG_X_OKAPI_TENANT, X_OKAPI_TOKEN));

    assertThat(errors, notNullValue());
    assertThat(errors.getErrors(), hasSize(1));
    assertThat(errors.getErrors().get(0).getMessage(), equalTo(MOD_CONFIG_ERROR.getDescription()));
    assertThat(errors.getErrors().get(0).getCode(), equalTo(MOD_CONFIG_ERROR.getCode()));
  }

  private Errors transitionToApprovedWithError(String invoiceSamplePath, Headers headers) {
    InvoiceLine invoiceLine = getMockAsJson(INVOICE_LINE_SAMPLE_PATH).mapTo(InvoiceLine.class);

    Invoice reqData = getMockAsJson(invoiceSamplePath).mapTo(Invoice.class);
    invoiceLine.setId(UUID.randomUUID().toString());
    invoiceLine.setInvoiceId(reqData.getId());
    prepareMockVoucher(reqData.getId());
    addMockEntry(INVOICE_LINES, JsonObject.mapFrom(invoiceLine));


    reqData.setStatus(Invoice.Status.APPROVED);

    String id = reqData.getId();
    String jsonBody = JsonObject.mapFrom(reqData).encode();
    return verifyPut(String.format(INVOICE_ID_PATH, id), jsonBody, headers, APPLICATION_JSON, 500)
      .then()
      .extract()
      .body().as(Errors.class);
  }


  @Test
  public void testTransitionToApprovedWithoutToken() {
    logger.info("=== Test transition invoice to Approved without token ===");

    Errors errors = transitionToApprovedWithError(REVIEWED_INVOICE_WITH_EXISTING_VOUCHER_SAMPLE_PATH, prepareHeaders(X_OKAPI_URL, X_OKAPI_TENANT));

    assertThat(errors, notNullValue());
    assertThat(errors.getErrors(), hasSize(1));
    assertThat(errors.getErrors().get(0).getMessage(), equalTo(MOD_CONFIG_ERROR.getDescription()));
    assertThat(errors.getErrors().get(0).getCode(), equalTo(MOD_CONFIG_ERROR.getCode()));
  }

  @Test
  public void testTransitionToApprovedWithoutInvoiceLines() {
    logger.info("=== Test transition invoice to Approved without invoiceLines should fail ===");

    Invoice reqData = getMockAsJson(REVIEWED_INVOICE_WITH_EXISTING_VOUCHER_SAMPLE_PATH).mapTo(Invoice.class);

    reqData.setStatus(Invoice.Status.APPROVED);

    String id = reqData.getId();
    String jsonBody = JsonObject.mapFrom(reqData).encode();

    Errors errors = verifyPut(String.format(INVOICE_ID_PATH, id), jsonBody, APPLICATION_JSON, 500)
      .then()
        .extract()
          .body().as(Errors.class);

    assertThat(errors, notNullValue());
    assertThat(errors.getErrors(), hasSize(1));
    assertThat(errors.getErrors().get(0).getMessage(), equalTo(NO_INVOICE_LINES_ERROR_MSG));

  }

  @Test
  public void testTransitionToApprovedWithInternalServerErrorFromGetInvoiceLines() {
    logger.info("=== Test transition invoice to Approved with Internal Server Error when retrieving invoiceLines ===");

    Headers headers = prepareHeaders(X_OKAPI_URL, MockServer.GET_INVOICE_LINES_ERROR_X_OKAPI_TENANT, X_OKAPI_TOKEN);
    Errors errors = transitionToApprovedWithError(REVIEWED_INVOICE_WITH_EXISTING_VOUCHER_SAMPLE_PATH, headers);

    assertThat(errors, notNullValue());
    assertThat(errors.getErrors(), hasSize(1));
    assertThat(errors.getErrors().get(0).getMessage(), equalTo(javax.ws.rs.core.Response.Status.INTERNAL_SERVER_ERROR.getReasonPhrase()));

  }

  @Test
  public void testTransitionToApprovedFundsNotFound() {
    logger.info("=== Test transition invoice to Approved when fund not found ===");

    InvoiceLine invoiceLine = getMockAsJson(INVOICE_LINE_SAMPLE_PATH).mapTo(InvoiceLine.class);
    Invoice reqData = getMockAsJson(REVIEWED_INVOICE_WITH_EXISTING_VOUCHER_SAMPLE_PATH).mapTo(Invoice.class);
    reqData.setStatus(Invoice.Status.APPROVED);
    String id = reqData.getId();
    invoiceLine.setId(UUID.randomUUID().toString());
    invoiceLine.setInvoiceId(id);
    invoiceLine.getFundDistributions().get(0).setFundId(ID_DOES_NOT_EXIST);

    addMockEntry(INVOICE_LINES, JsonObject.mapFrom(invoiceLine));

    String jsonBody = JsonObject.mapFrom(reqData).encode();
    Errors errors = verifyPut(String.format(INVOICE_ID_PATH, id), jsonBody, APPLICATION_JSON, 500)
      .then()
      .extract()
      .body().as(Errors.class);

    assertThat(errors, notNullValue());
    assertThat(errors.getErrors(), hasSize(1));
    Error error = errors.getErrors().get(0);
    assertThat(error.getMessage(), equalTo(FUNDS_NOT_FOUND.getDescription()));
    assertThat(error.getCode(), equalTo(FUNDS_NOT_FOUND.getCode()));
    assertThat(error.getParameters().get(0).getValue(), containsString(ID_DOES_NOT_EXIST));
  }

  @Test
  public void testTransitionToApprovedWithInternalServerErrorFromGetFunds() {
    logger.info("=== Test transition invoice to Approved with Internal Server Error when retrieving funds ===");

    Headers headers = prepareHeaders(X_OKAPI_URL, MockServer.GET_FUNDS_ERROR_X_OKAPI_TENANT, X_OKAPI_TOKEN);
    Errors errors = transitionToApprovedWithError(REVIEWED_INVOICE_WITH_EXISTING_VOUCHER_SAMPLE_PATH, headers);

    assertThat(errors, notNullValue());
    assertThat(errors.getErrors(), hasSize(1));
    assertThat(errors.getErrors().get(0).getCode(), equalTo(GENERIC_ERROR_CODE.getCode()));
    assertThat(errors.getErrors().get(0).getMessage(), equalTo(javax.ws.rs.core.Response.Status.INTERNAL_SERVER_ERROR.getReasonPhrase()));
  }

  @Test
  public void testTransitionToApprovedWithErrorFromGetVouchersByInvoiceId() {
    logger.info("=== Test transition invoice to Approved when searching existing voucher ===");

    Headers headers = prepareHeaders(X_OKAPI_URL, MockServer.GET_VOUCHERS_ERROR_X_OKAPI_TENANT, X_OKAPI_TOKEN);
    Errors errors = transitionToApprovedWithError(REVIEWED_INVOICE_WITH_EXISTING_VOUCHER_SAMPLE_PATH, headers);

    assertThat(errors, notNullValue());
    assertThat(errors.getErrors(), hasSize(1));
    assertThat(errors.getErrors().get(0).getCode(), equalTo(GENERIC_ERROR_CODE.getCode()));
  }

  @Test
  public void testTransitionToApprovedWithErrorFromUpdateVoucherById() {
    logger.info("=== Test transition invoice to Approved with error when updating existing voucher  ===");

    Headers headers = prepareHeaders(X_OKAPI_URL, MockServer.UPDATE_VOUCHER_ERROR_X_OKAPI_TENANT, X_OKAPI_TOKEN);
    Errors errors = transitionToApprovedWithError(REVIEWED_INVOICE_WITH_EXISTING_VOUCHER_SAMPLE_PATH, headers);

    assertThat(errors, notNullValue());
    assertThat(errors.getErrors(), hasSize(1));
    assertThat(errors.getErrors().get(0).getCode(), equalTo(GENERIC_ERROR_CODE.getCode()));
  }

  @Test
  public void testTransitionToApprovedWithErrorFromCreateVoucher() {
    logger.info("=== Test transition invoice to Approved with error when creating voucher  ===");

    Headers headers = prepareHeaders(X_OKAPI_URL, MockServer.CREATE_VOUCHER_ERROR_X_OKAPI_TENANT, X_OKAPI_TOKEN);
    Errors errors = transitionToApprovedWithError(REVIEWED_INVOICE_SAMPLE_PATH, headers);

    assertThat(errors, notNullValue());
    assertThat(errors.getErrors(), hasSize(1));
    assertThat(errors.getErrors().get(0).getCode(), equalTo(GENERIC_ERROR_CODE.getCode()));
  }

  @Test
  public void testTransitionToApprovedWithErrorFromGetVoucherLines() {
    logger.info("=== Test transition invoice to Approved with error when getting voucherLines  ===");

    Headers headers = prepareHeaders(X_OKAPI_URL, MockServer.GET_VOUCHER_LINE_ERROR_X_OKAPI_TENANT, X_OKAPI_TOKEN);
    Errors errors = transitionToApprovedWithError(REVIEWED_INVOICE_WITH_EXISTING_VOUCHER_SAMPLE_PATH, headers);

    assertThat(errors, notNullValue());
    assertThat(errors.getErrors(), hasSize(1));
    assertThat(errors.getErrors().get(0).getCode(), equalTo(GENERIC_ERROR_CODE.getCode()));
  }

  @Test
  public void testTransitionToApprovedWithErrorFromDeleteVoucherLines() {
    logger.info("=== Test transition invoice to Approved with error when deleting voucherLines  ===");

    Headers headers = prepareHeaders(X_OKAPI_URL, MockServer.DELETE_VOUCHER_LINE_ERROR_X_OKAPI_TENANT, X_OKAPI_TOKEN);
    Errors errors = transitionToApprovedWithError(REVIEWED_INVOICE_WITH_EXISTING_VOUCHER_SAMPLE_PATH, headers);

    assertThat(errors, notNullValue());
    assertThat(errors.getErrors(), hasSize(1));
    assertThat(errors.getErrors().get(0).getCode(), equalTo(GENERIC_ERROR_CODE.getCode()));
  }

  @Test
  public void testTransitionToApprovedWithErrorFromCreateVoucherLines() {
    logger.info("=== Test transition invoice to Approved with error when creating voucherLines  ===");

    Headers headers = prepareHeaders(X_OKAPI_URL, MockServer.CREATE_VOUCHER_LINE_ERROR_X_OKAPI_TENANT, X_OKAPI_TOKEN);
    Errors errors = transitionToApprovedWithError(REVIEWED_INVOICE_SAMPLE_PATH, headers);

    assertThat(errors, notNullValue());
    assertThat(errors.getErrors(), hasSize(1));
    assertThat(errors.getErrors().get(0).getCode(), equalTo(GENERIC_ERROR_CODE.getCode()));
  }

  private void verifyTransitionToApproved(Voucher voucherCreated, List<InvoiceLine> invoiceLines) {
    List<JsonObject> invoiceLinesSearches = serverRqRs.get(INVOICE_LINES, HttpMethod.GET);
    List<JsonObject> voucherLinesCreated = serverRqRs.get(VOUCHER_LINES, HttpMethod.POST);
    List<JsonObject> fundsSearches = serverRqRs.get(FUNDS, HttpMethod.GET);
    List<JsonObject> invoiceUpdates = serverRqRs.get(INVOICES, HttpMethod.PUT);

    assertThat(invoiceLinesSearches, notNullValue());
    assertThat(fundsSearches, notNullValue());
    assertThat(voucherLinesCreated, notNullValue());
    assertThat(invoiceUpdates, notNullValue());

    assertThat(invoiceLinesSearches, hasSize(invoiceLines.size()/MAX_IDS_FOR_GET_RQ + 1));
    List<Fund> funds = fundsSearches.get(0).mapTo(FundCollection.class).getFunds();
    assertThat(voucherLinesCreated, hasSize(getExpectedVoucherLinesQuantity(funds)));

    Invoice invoiceUpdate = invoiceUpdates.get(0).mapTo(Invoice.class);

    List<VoucherLine> voucherLines = voucherLinesCreated.stream().map(json -> json.mapTo(VoucherLine.class)).collect(Collectors.toList());

    assertThat(Invoice.Status.APPROVED, equalTo(invoiceUpdate.getStatus()));
    assertThat(invoiceUpdate.getVoucherNumber(), equalTo(voucherCreated.getVoucherNumber()));
    assertThat(invoiceUpdate.getId(), equalTo(voucherCreated.getInvoiceId()));
    assertThat(invoiceUpdate.getCurrency(), equalTo(voucherCreated.getInvoiceCurrency()));
    assertThat(MonetaryConversions.getExchangeRateProvider().getExchangeRate(voucherCreated.getInvoiceCurrency(), voucherCreated.getSystemCurrency()).getFactor().doubleValue(), equalTo(voucherCreated.getExchangeRate()));
    assertThat(voucherCreated.getAccountingCode(), notNullValue());
    assertThat(voucherCreated.getExportToAccounting(), is(false));
    assertThat(Voucher.Status.AWAITING_PAYMENT, equalTo(voucherCreated.getStatus()));
    assertThat(Voucher.Type.VOUCHER, equalTo(voucherCreated.getType()));

    assertThat(calculateVoucherAmount(voucherCreated, voucherLines), equalTo(voucherCreated.getAmount()));
    assertThat(getExpectedVoucherLinesQuantity(funds), equalTo(voucherLinesCreated.size()));
    invoiceLines.forEach(invoiceLine -> calculateInvoiceLineTotals(invoiceLine, invoiceUpdate));

    voucherLines.forEach(voucherLine -> {
      assertThat(voucherCreated.getId(), equalTo(voucherLine.getVoucherId()));
      assertThat(calculateVoucherLineAmount(voucherLine.getFundDistributions(), invoiceLines, voucherCreated), equalTo(voucherLine.getAmount()));
      assertThat(voucherLine.getFundDistributions(), hasSize(voucherLine.getSourceIds().size()));
    });
  }

  private int getExpectedVoucherLinesQuantity(List<Fund> fundsSearches) {
    return Math.toIntExact(fundsSearches.stream().map(Fund::getExternalAccountNo).distinct().count());
  }

  @Test
  public void testUpdateValidInvoiceTransitionToPaidWithMissingPoLine() {
    logger.info("=== Test transition invoice to paid with deleted associated poLine ===");

    Invoice reqData = getMockAsJson(APPROVED_INVOICE_SAMPLE_PATH).mapTo(Invoice.class).withStatus(Invoice.Status.PAID);
    String id = reqData.getId();

    InvoiceLine invoiceLine = getMockAsJson(INVOICE_LINE_SAMPLE_PATH).mapTo(InvoiceLine.class);
    invoiceLine.setInvoiceId(id);
    invoiceLine.setPoLineId(ID_DOES_NOT_EXIST);

    addMockEntry(INVOICE_LINES, JsonObject.mapFrom(invoiceLine));
    prepareMockVoucher(id);


    addMockEntry(INVOICE_LINES, JsonObject.mapFrom(invoiceLine));

    String jsonBody = JsonObject.mapFrom(reqData).encode();

    Errors errors = verifyPut(String.format(INVOICE_ID_PATH, id), jsonBody, APPLICATION_JSON, 500).then().extract().body().as(Errors.class);
    assertThat(serverRqRs.get(INVOICES, HttpMethod.PUT), nullValue());
    assertThat(errors.getErrors(), hasSize(1));
    assertThat(errors.getErrors().get(0).getCode(), equalTo(PO_LINE_NOT_FOUND.getCode()));
    assertThat(errors.getErrors().get(0).getParameters().get(0).getValue(), equalTo(ID_DOES_NOT_EXIST));
  }

  @Test
  public void testUpdateValidInvoiceTransitionToPaidWitErrorOnPoLineUpdate() {
    logger.info("=== Test transition invoice to paid with server error poLine update ===");

    Invoice reqData = getMockAsJson(APPROVED_INVOICE_SAMPLE_PATH).mapTo(Invoice.class).withStatus(Invoice.Status.PAID);
    String id = reqData.getId();

    InvoiceLine invoiceLine = getMockAsJson(INVOICE_LINE_SAMPLE_PATH).mapTo(InvoiceLine.class);
    invoiceLine.setInvoiceId(id);
    invoiceLine.setPoLineId(ID_FOR_INTERNAL_SERVER_ERROR_PUT);

    addMockEntry(INVOICE_LINES, JsonObject.mapFrom(invoiceLine));
    prepareMockVoucher(id);

    String jsonBody = JsonObject.mapFrom(reqData).encode();

    Errors errors = verifyPut(String.format(INVOICE_ID_PATH, id), jsonBody, APPLICATION_JSON, 500).as(Errors.class);
    assertThat(serverRqRs.get(INVOICES, HttpMethod.PUT), nullValue());
    assertThat(errors.getErrors(), hasSize(1));
    assertThat(errors.getErrors().get(0).getCode(), equalTo(PO_LINE_UPDATE_FAILURE.getCode()));
    assertThat(serverRqRs.get(INVOICES, HttpMethod.PUT), nullValue());
  }

  @Test
  public void testUpdateValidInvoiceTransitionToPaid() {
    logger.info("=== Test transition invoice to paid and mixed releaseEncumbrance ===");

    Invoice reqData = getMockAsJson(APPROVED_INVOICE_SAMPLE_PATH).mapTo(Invoice.class).withStatus(Invoice.Status.PAID);
    String id = reqData.getId();

    prepareMockVoucher(id);

    String jsonBody = JsonObject.mapFrom(reqData).encode();

    verifyPut(String.format(INVOICE_ID_PATH, id), jsonBody, "", 204);
    assertThat(serverRqRs.get(INVOICES, HttpMethod.PUT).get(0).getString(STATUS), is(Invoice.Status.PAID.value()));

    validatePoLinesPaymentStatus();
    assertThatVoucherPaid();
  }

  @Test
  public void testUpdateInvoiceTransitionToPaidNoVoucherUpdate() {
    logger.info("=== Test transition invoice to paid - voucher already paid ===");

    Invoice reqData = getMockAsJson(APPROVED_INVOICE_SAMPLE_PATH).mapTo(Invoice.class).withStatus(Invoice.Status.PAID);
    String id = reqData.getId();

    // Prepare already paid voucher
    Voucher voucher = getMockAsJson(VOUCHERS_LIST_PATH).mapTo(VoucherCollection.class).getVouchers().get(0);
    voucher.setInvoiceId(id);
    voucher.setStatus(Voucher.Status.PAID);
    addMockEntry(VOUCHERS, JsonObject.mapFrom(voucher));

    String jsonBody = JsonObject.mapFrom(reqData).encode();

    verifyPut(String.format(INVOICE_ID_PATH, id), jsonBody, "", 204);

    assertThat(getRqRsEntries(HttpMethod.GET, VOUCHERS), hasSize(1));
    assertThat(getRqRsEntries(HttpMethod.PUT, VOUCHERS), empty());
    assertThat(getRqRsEntries(HttpMethod.PUT, INVOICES), hasSize(1));
    assertThat(getRqRsEntries(HttpMethod.PUT, INVOICES).get(0).getString(STATUS), is(Invoice.Status.PAID.value()));
  }

  @Test
  public void testUpdateInvoiceTransitionToPaidNoVoucher() {
    logger.info("=== Test transition invoice to paid - no voucher found ===");

    Invoice reqData = getMockAsJson(APPROVED_INVOICE_SAMPLE_PATH).mapTo(Invoice.class).withStatus(Invoice.Status.PAID);
    prepareMockVoucher(ID_DOES_NOT_EXIST);

    String url = String.format(INVOICE_ID_PATH, reqData.getId());
    Errors errors = verifyPut(url, JsonObject.mapFrom(reqData), APPLICATION_JSON, 500).as(Errors.class);

    assertThat(errors.getErrors(), hasSize(1));
    assertThat(errors.getErrors().get(0).getCode(), equalTo(VOUCHER_NOT_FOUND.getCode()));
    assertThat(getRqRsEntries(HttpMethod.GET, INVOICES), hasSize(1));
    assertThat(getRqRsEntries(HttpMethod.PUT, INVOICES), empty());
    assertThat(getRqRsEntries(HttpMethod.PUT, VOUCHERS), empty());
  }

  @Test
  public void testUpdateInvoiceTransitionToPaidVoucherUpdateFailure() {
    logger.info("=== Test transition invoice to paid - voucher update failure ===");

    Invoice reqData = getMockAsJson(APPROVED_INVOICE_SAMPLE_PATH).mapTo(Invoice.class).withStatus(Invoice.Status.PAID);
    prepareMockVoucher(reqData.getId(), true);

    String url = String.format(INVOICE_ID_PATH, reqData.getId());
    Errors errors = verifyPut(url, JsonObject.mapFrom(reqData), APPLICATION_JSON, 500).as(Errors.class);

    assertThat(errors.getErrors(), hasSize(1));
    assertThat(errors.getErrors().get(0).getCode(), equalTo(VOUCHER_UPDATE_FAILURE.getCode()));
    assertThat(getRqRsEntries(HttpMethod.PUT, INVOICES), empty());
  }

  private void validatePoLinesPaymentStatus() {

    final List<CompositePoLine> updatedPoLines = getRqRsEntries(HttpMethod.PUT, ORDER_LINES).stream()
      .map(poLine -> poLine.mapTo(CompositePoLine.class))
      .collect(Collectors.toList());

    assertThat(updatedPoLines, not(empty()));

    Map<String, List<InvoiceLine>> invoiceLines = getRqRsEntries(HttpMethod.GET, INVOICE_LINES).get(0)
      .mapTo(InvoiceLineCollection.class)
      .getInvoiceLines()
      .stream()
      .collect(groupingBy(InvoiceLine::getPoLineId));

    assertThat(invoiceLines.size(), equalTo(updatedPoLines.size()));

    for (Map.Entry<String, List<InvoiceLine>> poLineIdWithInvoiceLines : invoiceLines.entrySet()) {
      CompositePoLine poLine = updatedPoLines.stream()
        .filter(compositePoLine -> compositePoLine.getId().equals(poLineIdWithInvoiceLines.getKey()))
        .findFirst()
        .orElseThrow(NullPointerException::new);
      CompositePoLine.PaymentStatus expectedStatus = poLineIdWithInvoiceLines.getValue().stream()
        .anyMatch(InvoiceLine::getReleaseEncumbrance) ? CompositePoLine.PaymentStatus.FULLY_PAID : CompositePoLine.PaymentStatus.PARTIALLY_PAID;
      assertThat(expectedStatus, is(poLine.getPaymentStatus()));
    }
  }

  private void assertThatVoucherPaid() {
    assertThat(getRqRsEntries(HttpMethod.PUT, VOUCHERS), hasSize(1));
    assertThat(getRqRsEntries(HttpMethod.PUT, VOUCHERS).get(0).mapTo(Voucher.class).getStatus(), is(Voucher.Status.PAID));
  }

  @Test
  public void testUpdateValidInvoiceTransitionToPaidReleaseEncumbranceFalse() {
    logger.info("=== Test transition invoice to paid and releaseEncumbrance false for all invoice lines ===");

    Invoice reqData = getMockAsJson(APPROVED_INVOICE_SAMPLE_PATH).mapTo(Invoice.class).withStatus(Invoice.Status.PAID);
    String id = reqData.getId();

    // Prepare invoice lines
    for (int i = 0; i < 3; i++) {
      InvoiceLine invoiceLine = getMockAsJson(INVOICE_LINE_SAMPLE_PATH).mapTo(InvoiceLine.class);
      invoiceLine.setId(UUID.randomUUID().toString());
      invoiceLine.setInvoiceId(id);
      invoiceLine.setPoLineId(EXISTENT_PO_LINE_ID);
      invoiceLine.setReleaseEncumbrance(false);
      addMockEntry(INVOICE_LINES, JsonObject.mapFrom(invoiceLine));
    }

    prepareMockVoucher(id);

    verifyPut(String.format(INVOICE_ID_PATH, id), JsonObject.mapFrom(reqData), "", 204);

    assertThat(serverRqRs.get(INVOICES, HttpMethod.PUT).get(0).getString(STATUS), is(Invoice.Status.PAID.value()));
    assertThat(serverRqRs.get(INVOICE_LINES, HttpMethod.GET), notNullValue());
    assertThat(serverRqRs.get(INVOICE_LINES, HttpMethod.GET).get(0).mapTo(InvoiceLineCollection.class).getTotalRecords(), equalTo(3));
    assertThat(serverRqRs.get(ORDER_LINES, HttpMethod.PUT), notNullValue());
    assertThat(serverRqRs.get(ORDER_LINES, HttpMethod.PUT), hasSize(1));
    assertThat(serverRqRs.get(ORDER_LINES, HttpMethod.PUT).get(0).mapTo(CompositePoLine.class).getPaymentStatus(), equalTo(CompositePoLine.PaymentStatus.PARTIALLY_PAID));
  }

  @Test
  public void testUpdateValidInvoiceTransitionToPaidReleaseEncumbranceFalseNoPoLineUpdate() {
    logger.info("=== Test transition invoice to paid and releaseEncumbrance false for invoice line without poLine update ===");

    Invoice reqData = getMockAsJson(APPROVED_INVOICE_SAMPLE_PATH).mapTo(Invoice.class).withStatus(Invoice.Status.PAID);
    String id = reqData.getId();

    InvoiceLine invoiceLine = getMockAsJson(INVOICE_LINE_SAMPLE_PATH).mapTo(InvoiceLine.class);
    invoiceLine.setId(UUID.randomUUID().toString());
    invoiceLine.setInvoiceId(id);
    invoiceLine.setPoLineId(EXISTENT_PO_LINE_ID);
    invoiceLine.setReleaseEncumbrance(false);

    CompositePoLine poLine = getMockAsJson(String.format("%s%s.json", PO_LINE_MOCK_DATA_PATH, EXISTENT_PO_LINE_ID)).mapTo(CompositePoLine.class);
    poLine.setId(EXISTENT_PO_LINE_ID);
    poLine.setPaymentStatus(CompositePoLine.PaymentStatus.PARTIALLY_PAID);

    addMockEntry(INVOICE_LINES, JsonObject.mapFrom(invoiceLine));
    addMockEntry(ORDER_LINES, JsonObject.mapFrom(poLine));
    prepareMockVoucher(id);

    verifyPut(String.format(INVOICE_ID_PATH, id), JsonObject.mapFrom(reqData), "", 204);

    assertThat(getRqRsEntries(HttpMethod.PUT, INVOICES).get(0).getString(STATUS), is(Invoice.Status.PAID.value()));
    assertThat(getRqRsEntries(HttpMethod.GET, INVOICE_LINES), hasSize(1));
    assertThat(getRqRsEntries(HttpMethod.GET, INVOICE_LINES).get(0).mapTo(InvoiceLineCollection.class).getTotalRecords(), equalTo(1));
    assertThat(getRqRsEntries(HttpMethod.PUT, ORDER_LINES), empty());
    assertThatVoucherPaid();
  }

  private void prepareMockVoucher(String invoiceId) {
    prepareMockVoucher(invoiceId, false);
  }

  private void prepareMockVoucher(String invoiceId, boolean failOnUpdate) {
    Voucher voucher = getMockAsJson(VOUCHERS_LIST_PATH).mapTo(VoucherCollection.class).getVouchers().get(0);
    voucher.setInvoiceId(invoiceId);
    if (failOnUpdate) {
      voucher.setId(ID_FOR_INTERNAL_SERVER_ERROR_PUT);
    }
    addMockEntry(VOUCHERS, JsonObject.mapFrom(voucher));
  }

  @Test
  public void testUpdateValidInvoiceTransitionToPaidReleaseEncumbranceTrue() {
    logger.info("=== Test transition invoice to paid and releaseEncumbrance true for all invoice lines ===");
    List<InvoiceLine> invoiceLines = new ArrayList<>();
    List<CompositePoLine> poLines = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      invoiceLines.add(getMockAsJson(INVOICE_LINE_SAMPLE_PATH).mapTo(InvoiceLine.class));
      poLines.add(getMockAsJson(String.format("%s%s.json", PO_LINE_MOCK_DATA_PATH, EXISTENT_PO_LINE_ID)).mapTo(CompositePoLine.class));
    }

    Invoice reqData = getMockAsJson(APPROVED_INVOICE_SAMPLE_PATH).mapTo(Invoice.class).withStatus(Invoice.Status.PAID);
    String id = reqData.getId();
    for (int i = 0; i < 3; i++) {
      invoiceLines.get(i).setId(UUID.randomUUID().toString());
      invoiceLines.get(i).setInvoiceId(reqData.getId());
      String poLineId = UUID.randomUUID().toString();
      invoiceLines.get(i).setPoLineId(poLineId);
      poLines.get(i).setId(poLineId);
    }

    invoiceLines.forEach(line -> addMockEntry(INVOICE_LINES, JsonObject.mapFrom(line)));
    poLines.forEach(line -> addMockEntry(ORDER_LINES, JsonObject.mapFrom(line)));
    prepareMockVoucher(id);

    verifyPut(String.format(INVOICE_ID_PATH, id), JsonObject.mapFrom(reqData), "", 204);

    assertThat(getRqRsEntries(HttpMethod.PUT, INVOICES).get(0).getString(STATUS), is(Invoice.Status.PAID.value()));
    assertThat(getRqRsEntries(HttpMethod.GET, INVOICE_LINES), hasSize(1));
    assertThat(getRqRsEntries(HttpMethod.GET, INVOICE_LINES).get(0).mapTo(InvoiceLineCollection.class).getTotalRecords(), equalTo(3));
    assertThat(getRqRsEntries(HttpMethod.PUT, ORDER_LINES), hasSize(3));
    getRqRsEntries(HttpMethod.PUT, ORDER_LINES).stream()
      .map(entries -> entries.mapTo(CompositePoLine.class))
      .forEach(compositePoLine -> assertThat(compositePoLine.getPaymentStatus(), equalTo(CompositePoLine.PaymentStatus.FULLY_PAID)));
    assertThatVoucherPaid();

  }

  @Test
  public void testUpdateInvoiceWithLockedTotalButWithoutTotal() throws IOException {
    logger.info("=== Test validation updating invoice without total which is locked ===");

    // ===  Preparing invoice for test  ===
    Invoice invoice = new JsonObject(getMockData(APPROVED_INVOICE_SAMPLE_PATH)).mapTo(Invoice.class);
    invoice.setLockTotal(true);
    invoice.setTotal(null);

    // ===  Run test  ===
    Errors errors = verifyPut(String.format(INVOICE_ID_PATH, invoice.getId()), JsonObject.mapFrom(invoice), APPLICATION_JSON, 422)
      .as(Errors.class);

    assertThat(errors.getErrors(), hasSize(1));
    assertThat(errors.getErrors().get(0).getCode(), equalTo(INVOICE_TOTAL_REQUIRED.getCode()));
    assertThat(serverRqRs.size(), equalTo(0));
  }

  @Test
  public void testUpdateNotExistentInvoice() throws IOException {
    logger.info("=== Test update non existent invoice===");

    String jsonBody  = getMockData(APPROVED_INVOICE_SAMPLE_PATH);

    verifyPut(String.format(INVOICE_ID_PATH, ID_DOES_NOT_EXIST), jsonBody, APPLICATION_JSON, 404);
  }

  @Test
  public void testUpdateInvoiceInternalErrorOnStorage() throws IOException {
    logger.info("=== Test update invoice by id with internal server error from storage ===");

    String jsonBody  = getMockData(APPROVED_INVOICE_SAMPLE_PATH);

    verifyPut(String.format(INVOICE_ID_PATH, ID_FOR_INTERNAL_SERVER_ERROR), jsonBody, APPLICATION_JSON, 500);
  }

  @Test
  public void testUpdateInvoiceByIdWithInvalidFormat() throws IOException {

    String jsonBody  = getMockData(APPROVED_INVOICE_SAMPLE_PATH);

    verifyPut(String.format(INVOICE_ID_PATH, ID_BAD_FORMAT), jsonBody, TEXT_PLAIN, 400);
  }

  @Test
  public void testUpdateInvoiceBadLanguage() throws IOException {
    String jsonBody  = getMockData(APPROVED_INVOICE_SAMPLE_PATH);
    String endpoint = String.format(INVOICE_ID_WITH_LANG_PATH, VALID_UUID, INVALID_LANG) ;

    verifyPut(endpoint, jsonBody, TEXT_PLAIN, 400);
  }

  @Test
  public void testPostInvoicingInvoices() throws Exception {
    logger.info("=== Test create invoice without id and folioInvoiceNo ===");

    String body = getMockData(APPROVED_INVOICE_SAMPLE_PATH);

    final Invoice respData = verifyPostResponse(INVOICE_PATH, body, prepareHeaders(X_OKAPI_TENANT), APPLICATION_JSON, 201).as(Invoice.class);

    String poId = respData.getId();
    String folioInvoiceNo = respData.getFolioInvoiceNo();

    assertThat(poId, notNullValue());
    assertThat(folioInvoiceNo, notNullValue());
    assertThat(MockServer.serverRqRs.get(FOLIO_INVOICE_NUMBER, HttpMethod.GET), hasSize(1));
  }

  @Test
  public void testCreateInvoiceWithLockedTotalAndTwoAdjustments() throws IOException {
    logger.info("=== Test create invoice with locked total and 2 adjustments ===");

    // ===  Preparing invoice for test  ===
    Invoice invoice = new JsonObject(getMockData(APPROVED_INVOICE_SAMPLE_PATH)).mapTo(Invoice.class);
    invoice.setLockTotal(true);
    invoice.setTotal(15d);

    // ===  Run test  ===
    final Invoice resp = verifyPostResponse(INVOICE_PATH, JsonObject.mapFrom(invoice), prepareHeaders(X_OKAPI_TENANT),
        APPLICATION_JSON, 201).as(Invoice.class);

    /* The invoice has 2 not prorated adjustments one with fixed amount and another with percentage type */
    assertThat(resp.getAdjustmentsTotal(), equalTo(5.06d));
    assertThat(resp.getSubTotal(), equalTo(0d));
    assertThat(resp.getTotal(), equalTo(15d));

    // Verify that expected number of external calls made
    assertThat(serverRqRs.cellSet(), hasSize(2));
    assertThat(getRqRsEntries(HttpMethod.GET, FOLIO_INVOICE_NUMBER), hasSize(1));
    assertThat(getRqRsEntries(HttpMethod.POST, INVOICES), hasSize(1));
  }

  @Test
  public void testCreateInvoiceWithLockedTotalAndTwoProratedAdjustments() throws IOException {
    logger.info("=== Test create invoice with locked total and 2 prorated adjustments ===");

    // ===  Preparing invoice for test  ===
    Invoice invoice = new JsonObject(getMockData(APPROVED_INVOICE_SAMPLE_PATH)).mapTo(Invoice.class);
    invoice.getAdjustments().forEach(adj -> adj.setProrate(Adjustment.Prorate.BY_AMOUNT));
    invoice.setLockTotal(true);
    invoice.setTotal(15d);

    // ===  Run test  ===
    final Invoice resp = verifyPostResponse(INVOICE_PATH, JsonObject.mapFrom(invoice), prepareHeaders(X_OKAPI_TENANT),
      APPLICATION_JSON, 201).as(Invoice.class);

    /* The invoice has 2 not prorated adjustments one with fixed amount and another with percentage type */
    assertThat(resp.getAdjustmentsTotal(), equalTo(0d));
    assertThat(resp.getSubTotal(), equalTo(0d));
    assertThat(resp.getTotal(), equalTo(15d));

    // Verify that expected number of external calls made
    assertThat(serverRqRs.cellSet(), hasSize(2));
    assertThat(getRqRsEntries(HttpMethod.GET, FOLIO_INVOICE_NUMBER), hasSize(1));
    assertThat(getRqRsEntries(HttpMethod.POST, INVOICES), hasSize(1));
  }

  @Test
  public void testCreateInvoiceWithNonLockedTotalAndWithoutAdjustments() throws IOException {
    logger.info("=== Test create invoice without total and no adjustments ===");

    // ===  Preparing invoice for test  ===
    Invoice invoice = new JsonObject(getMockData(APPROVED_INVOICE_SAMPLE_PATH)).mapTo(Invoice.class);
    invoice.setLockTotal(false);
    invoice.setAdjustments(null);
    invoice.setTotal(null);

    // ===  Run test  ===
    final Invoice resp = verifyPostResponse(INVOICE_PATH, JsonObject.mapFrom(invoice), prepareHeaders(X_OKAPI_TENANT),
      APPLICATION_JSON, 201).as(Invoice.class);

    assertThat(resp.getAdjustmentsTotal(), equalTo(0d));
    assertThat(resp.getSubTotal(), equalTo(0d));
    assertThat(resp.getTotal(), equalTo(0d));

    // Verify that expected number of external calls made
    assertThat(serverRqRs.cellSet(), hasSize(2));
    assertThat(getRqRsEntries(HttpMethod.GET, FOLIO_INVOICE_NUMBER), hasSize(1));
    assertThat(getRqRsEntries(HttpMethod.POST, INVOICES), hasSize(1));
  }

  @Test
  public void testPostInvoicingInvoicesErrorFromStorage() throws Exception {
    logger.info("=== Test create invoice without with error from storage on saving invoice  ===");

    Invoice reqData = getMockAsJson(APPROVED_INVOICE_SAMPLE_PATH).mapTo(Invoice.class);
    reqData.setId(null);
    reqData.setFolioInvoiceNo(null);
    String body = getMockData(APPROVED_INVOICE_SAMPLE_PATH);

    verifyPostResponse(INVOICE_PATH, body, prepareHeaders(ERROR_X_OKAPI_TENANT), APPLICATION_JSON, 500);

    assertThat(MockServer.serverRqRs.get(FOLIO_INVOICE_NUMBER, HttpMethod.GET), hasSize(1));
  }

  @Test
  public void testPostInvoicingInvoicesWithInvoiceNumberGenerationFail() throws IOException {
    logger.info("=== Test create invoice without error from storage on folioInvoiceNo generation  ===");

    Invoice reqData = getMockAsJson(APPROVED_INVOICE_SAMPLE_PATH).mapTo(Invoice.class);
    reqData.setId(null);
    reqData.setFolioInvoiceNo(null);
    String body = getMockData(APPROVED_INVOICE_SAMPLE_PATH);

    verifyPostResponse(INVOICE_PATH, body, prepareHeaders(INVOICE_NUMBER_ERROR_X_OKAPI_TENANT), APPLICATION_JSON, 500);

  }

  @Test
  public void testCreateInvoiceWithLockedTotalButWithoutTotal() throws IOException {
    logger.info("=== Test validation on creating Invoice without total which is locked ===");

    // ===  Preparing invoice for test  ===
    Invoice invoice = new JsonObject(getMockData(APPROVED_INVOICE_SAMPLE_PATH)).mapTo(Invoice.class);
    invoice.setLockTotal(true);
    invoice.setTotal(null);

    // ===  Run test  ===
    Errors errors = verifyPostResponse(INVOICE_PATH, JsonObject.mapFrom(invoice), prepareHeaders(X_OKAPI_TENANT), APPLICATION_JSON, 422)
      .as(Errors.class);

    assertThat(errors.getErrors(), hasSize(1));
    assertThat(errors.getErrors().get(0).getCode(), equalTo(INVOICE_TOTAL_REQUIRED.getCode()));
    assertThat(serverRqRs.size(), equalTo(0));
  }

  @Test
  public void testGetInvoiceNumber() {
    logger.info("=== Test Get Invoice number - not implemented ===");

    verifyGet(INVOICE_NUMBER_PATH, TEXT_PLAIN, 500);
  }

  @Test
  public void testDeleteInvoiceByValidId() {
    verifyDeleteResponse(String.format(INVOICE_ID_PATH, VALID_UUID), "", 204);
  }

  @Test
  public void testDeleteInvoiceByIdWithInvalidFormat() {
    verifyDeleteResponse(String.format(INVOICE_ID_PATH, ID_BAD_FORMAT), TEXT_PLAIN, 400);
  }

  @Test
  public void testDeleteNotExistentInvoice() {
    verifyDeleteResponse(String.format(INVOICE_ID_PATH, ID_DOES_NOT_EXIST), APPLICATION_JSON, 404);
  }

  @Test
  public void testDeleteInvoiceInternalErrorOnStorage() {
    verifyDeleteResponse(String.format(INVOICE_ID_PATH, ID_FOR_INTERNAL_SERVER_ERROR), APPLICATION_JSON, 500);
  }

  @Test
  public void testDeleteInvoiceBadLanguage() {

    String endpoint = String.format(INVOICE_ID_WITH_LANG_PATH, VALID_UUID, INVALID_LANG) ;

    verifyDeleteResponse(endpoint, TEXT_PLAIN, 400);
  }

  @Test
  public void testNumberOfRequests() {
    logger.info("=== Test nuber of requests on invoice PUT ===");

    // Invoice status APPROVED, PAID, CANCELLED - expect invoice updating with GET invoice rq + PUT invoice rq by statuses processable flow
    Invoice.Status[] processableStatuses = {Invoice.Status.APPROVED, Invoice.Status.PAID, Invoice.Status.CANCELLED};
    checkNumberOfRequests(processableStatuses);

    // Invoice status APPROVED, PAID, CANCELLED - expect invoice updating with GET invoice rq + PUT invoice rq without statuses processable flow
    Invoice.Status[] nonProcessableStatuses = {Invoice.Status.OPEN, Invoice.Status.REVIEWED};
    checkNumberOfRequests(nonProcessableStatuses);
  }

  private void checkNumberOfRequests(Invoice.Status[] statuses) {
    // Invoice status open - expect no GET invoice rq + PUT invoice rq
    for(Invoice.Status status : statuses) {
      Invoice invoice = getMockAsJson(APPROVED_INVOICE_SAMPLE_PATH).mapTo(Invoice.class).withStatus(status);
      prepareMockVoucher(invoice.getId());

      verifyPut(String.format(INVOICE_ID_PATH, invoice.getId()), JsonObject.mapFrom(invoice).encode(), "", HttpStatus.SC_NO_CONTENT);

      assertThat(serverRqRs.row(INVOICES).get(HttpMethod.GET), hasSize(1));
      assertThat(serverRqRs.row(INVOICES).get(HttpMethod.PUT), hasSize(1));
      serverRqRs.clear();
    }
  }

  @Test
  public void testUpdateInvoiceWithProtectedFields() throws IllegalAccessException {
    logger.info("=== Test update invoice by id with protected fields (all fields set) ===");

    Invoice invoice = getMockAsJson(APPROVED_INVOICE_SAMPLE_PATH).mapTo(Invoice.class);

    Map<InvoiceProtectedFields, Object> allProtectedFieldsModification = new HashMap<>();

    List<Adjustment> adjustments = invoice.getAdjustments();
    adjustments.get(0).setValue(12345.54321);
    allProtectedFieldsModification.put(InvoiceProtectedFields.ADJUSTMENTS, adjustments);

    allProtectedFieldsModification.put(InvoiceProtectedFields.APPROVED_BY, UUID.randomUUID().toString());
    allProtectedFieldsModification.put(InvoiceProtectedFields.APPROVAL_DATE, new Date(System.currentTimeMillis()));
    allProtectedFieldsModification.put(InvoiceProtectedFields.CHK_SUBSCRIPTION_OVERLAP, true);
    allProtectedFieldsModification.put(InvoiceProtectedFields.CURRENCY, "TUGRIK");
    allProtectedFieldsModification.put(InvoiceProtectedFields.FOLIO_INVOICE_NO, "some_folio_inv_num");
    allProtectedFieldsModification.put(InvoiceProtectedFields.INVOICE_DATE, new Date(System.currentTimeMillis()));
    allProtectedFieldsModification.put(InvoiceProtectedFields.LOCK_TOTAL, true);
    allProtectedFieldsModification.put(InvoiceProtectedFields.PAYMENT_TERMS, "Payment now");
    allProtectedFieldsModification.put(InvoiceProtectedFields.SOURCE, UUID.randomUUID().toString());
    allProtectedFieldsModification.put(InvoiceProtectedFields.VOUCHER_NUMBER, "some_voucher_number");
    allProtectedFieldsModification.put(InvoiceProtectedFields.PAYMENT_ID, UUID.randomUUID().toString());
    allProtectedFieldsModification.put(InvoiceProtectedFields.VENDOR_ID, UUID.randomUUID().toString());

    List<String> poNumbers = invoice.getPoNumbers();
    poNumbers.add(0, "AB267798XYZ");
    allProtectedFieldsModification.put(InvoiceProtectedFields.PO_NUMBERS, poNumbers);

    checkPreventInvoiceModificationRule(invoice, allProtectedFieldsModification);

    // Check number of requests
    assertThat(serverRqRs.row(INVOICES).get(HttpMethod.GET), hasSize(1));
    // PUT request wasn't processed
    assertThat(serverRqRs.row(INVOICES).get(HttpMethod.PUT), nullValue());
  }

  @Test
  public void testUpdateInvoiceTotalValidation() throws IOException {
    logger.info("=== Test update invoice with locked total changing total value ===");

    Invoice invoice = new JsonObject(getMockData(APPROVED_INVOICE_SAMPLE_PATH)).mapTo(Invoice.class);
    invoice.setLockTotal(true);
    invoice.setTotal(15d);

    // Set record state which is returned from storage
    addMockEntry(INVOICES, JsonObject.mapFrom(invoice));

    // Set another total
    invoice.setTotal(10d);

    String url = String.format(INVOICE_ID_PATH, invoice.getId());
    Errors errors = verifyPut(url, JsonObject.mapFrom(invoice), "", HttpStatus.SC_BAD_REQUEST).as(Errors.class);

    assertThat(errors.getErrors(), hasSize(1));

    Error error = errors.getErrors().get(0);
    assertThat(error.getCode(), equalTo(PROHIBITED_FIELD_CHANGING.getCode()));
    Object[] failedFieldNames = getModifiedProtectedFields(error);
    assertThat(failedFieldNames, arrayContaining(InvoiceHelper.TOTAL));

    // Check number of requests
    assertThat(serverRqRs.row(INVOICES).get(HttpMethod.GET), hasSize(1));
    // PUT request wasn't processed
    assertThat(serverRqRs.row(INVOICES).get(HttpMethod.PUT), nullValue());
  }

  private void checkPreventInvoiceModificationRule(Invoice invoice, Map<InvoiceProtectedFields, Object> updatedFields) throws IllegalAccessException {
    invoice.setStatus(Invoice.Status.APPROVED);
    for (Map.Entry<InvoiceProtectedFields, Object> m : updatedFields.entrySet()) {
      FieldUtils.writeDeclaredField(invoice, m.getKey().getFieldName(), m.getValue(), true);
    }
    String body = JsonObject.mapFrom(invoice).encode();
    Errors errors = verifyPut(String.format(INVOICE_ID_PATH, invoice.getId()), body, "", HttpStatus.SC_BAD_REQUEST).as(Errors.class);

    // Only one error expected
    assertThat(errors.getErrors(), hasSize(1));

    Error error = errors.getErrors().get(0);
    assertThat(error.getCode(), equalTo(PROHIBITED_FIELD_CHANGING.getCode()));

    Object[] failedFieldNames = getModifiedProtectedFields(error);
    Object[] expected = updatedFields.keySet().stream().map(InvoiceProtectedFields::getFieldName).toArray();
    assertThat(failedFieldNames.length, is(expected.length));
    assertThat(expected, Matchers.arrayContainingInAnyOrder(failedFieldNames));
  }

  private Object[] getModifiedProtectedFields(Error error) {
    return Optional.of(error.getAdditionalProperties().get(PROTECTED_AND_MODIFIED_FIELDS))
      .map(obj -> (List) obj)
      .get()
      .toArray();
  }
}
