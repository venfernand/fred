package freenet.client;

import java.io.IOException;
import java.util.LinkedList;

import freenet.keys.ClientKey;
import freenet.keys.FreenetURI;
import freenet.keys.KeyBlock;
import freenet.keys.KeyDecodeException;
import freenet.support.Bucket;
import freenet.support.BucketTools;
import freenet.support.Logger;

/** Class that does the actual fetching. Does not have to have a user friendly
 * interface!
 */
class Fetcher {

	final FreenetURI origURI;
	final FetcherContext ctx;
	ArchiveContext archiveContext;
	
	public Fetcher(FreenetURI uri, FetcherContext ctx, ArchiveContext archiveContext) {
		this.origURI = uri;
		this.ctx = ctx;
		this.archiveContext = archiveContext;
	}

	public FetchResult run() throws FetchException {
		for(int i=0;i<ctx.maxArchiveRestarts;i++) {
			try {
				ClientMetadata dm = new ClientMetadata();
				return realRun(dm, 0, origURI, ctx.dontEnterImplicitArchives);
			} catch (ArchiveRestartException e) {
				archiveContext = new ArchiveContext();
				continue;
			} catch (MetadataParseException e) {
				throw new FetchException(e);
			} catch (ArchiveFailureException e) {
				if(e.getMessage().equals(ArchiveFailureException.TOO_MANY_LEVELS))
					throw new FetchException(FetchException.TOO_DEEP_ARCHIVE_RECURSION);
				throw new FetchException(e);
			}
		}
		throw new FetchException(FetchException.TOO_MANY_ARCHIVE_RESTARTS);
	}
	
	/**
	 * Fetch a key.
	 * @param dm The client metadata object to accumulate client metadata in.
	 * @param recursionLevel The recursion level. Incremented every time we enter
	 * realRun(). If it goes above a certain limit, we throw a FetchException.
	 * @param uri The URI to fetch.
	 * @return The data, complete with client metadata.
	 * @throws FetchException If we could not fetch the data.
	 * @throws MetadataParseException If we could not parse the metadata.
	 * @throws ArchiveFailureException If we could not extract data from an archive.
	 * @throws ArchiveRestartException 
	 */
	FetchResult realRun(ClientMetadata dm, int recursionLevel, FreenetURI uri, boolean dontEnterImplicitArchives) 
	throws FetchException, MetadataParseException, ArchiveFailureException, ArchiveRestartException {
		ClientKey key = ClientKey.getBaseKey(uri);
		LinkedList metaStrings = uri.listMetaStrings();
		
		recursionLevel++;
		if(recursionLevel > ctx.maxRecursionLevel)
			throw new FetchException(FetchException.TOO_MUCH_RECURSION);
		
		// Do the fetch
		KeyBlock block = ctx.client.getKey(key);
		
		byte[] data;
		try {
			data = block.decode(key);
		} catch (KeyDecodeException e1) {
			throw new FetchException(FetchException.BLOCK_DECODE_ERROR);
		}
		
		if(!key.isMetadata()) {
			// Just return the data
			try {
				return new FetchResult(dm, BucketTools.makeImmutableBucket(ctx.bucketFactory, data));
			} catch (IOException e) {
				Logger.error(this, "Could not capture data - disk full?: "+e, e);
			}
		}
		
		// Otherwise we need to parse the metadata
		
		Metadata metadata = Metadata.construct(data);
		
		FetchResult result = runMetadata(dm, recursionLevel, key, metaStrings, metadata, null, key.getURI(), dontEnterImplicitArchives);
		if(metaStrings.isEmpty()) return result;
		// Still got some meta-strings
		throw new FetchException(FetchException.HAS_MORE_METASTRINGS);
	}
	
	/**
	 * Fetch data, from metadata.
	 * @param recursionLevel The recursion level, from above. Not incremented here, as we will
	 * go through realRun() if the key changes, so the number of passes here is severely limited.
	 * @param key The key being fetched.
	 * @param metaStrings List of unused meta strings (to be used by manifests).
	 * @param metadata The parsed metadata to process.
	 * @param container The container in which this metadata is found.
	 * @return
	 * @throws MetadataParseException If we could not parse metadata from a sub-document. Will be
	 * converted to a FetchException above.
	 * @throws ArchiveFailureException If extracting data from an archive failed.
	 * @throws FetchException If the fetch failed for some reason.
	 * @throws ArchiveRestartException 
	 */
	private FetchResult runMetadata(ClientMetadata dm, int recursionLevel, ClientKey key, LinkedList metaStrings, 
			Metadata metadata, ArchiveHandler container, FreenetURI thisKey, boolean dontEnterImplicitArchives) 
	throws MetadataParseException, FetchException, ArchiveFailureException, ArchiveRestartException {
		
		if(metadata.isSimpleManifest()) {
			String name = (String) metaStrings.removeFirst();
			// Since metadata is a document, we just replace metadata here
			if(name == null) {
				metadata = metadata.getDefaultDocument();
			} else {
				metadata = metadata.getDocument(name);
				thisKey = thisKey.pushMetaString(name);
			}
			return runMetadata(dm, recursionLevel, key, metaStrings, metadata, container, thisKey, dontEnterImplicitArchives);
		} else if(metadata.isArchiveManifest()) {
			container = ctx.archiveManager.makeHandler(thisKey, metadata.getArchiveType());
			Bucket metadataBucket = container.getMetadata(archiveContext, ctx, dm, recursionLevel, true);
			metadata = Metadata.construct(metadataBucket);
			return runMetadata(dm, recursionLevel+1, key, metaStrings, metadata, container, thisKey, dontEnterImplicitArchives);
		} else if(metadata.isArchiveInternalRedirect()) {
			if(container == null)
				throw new FetchException(FetchException.NOT_IN_ARCHIVE);
			else {
				/* Implicit archive handling:
				 * Sooner or later we reach a SimpleFileRedirect to data, a Splitfile to data,
				 * or an ArchiveInternalRedirect to data.
				 * 
				 * In this case, if it is an archive type, if implicit archive handling is enabled, and if
				 * we have more meta-strings, we can try to enter it.
				 */
				if((!dontEnterImplicitArchives) && ArchiveManager.isUsableArchiveType(dm.getMIMEType()) && (!metaStrings.isEmpty())) {
					// Possible implicit archive inside archive?
					container = ctx.archiveManager.makeHandler(thisKey, ArchiveManager.getArchiveType(dm.getMIMEType()));
					Bucket metadataBucket = container.getMetadata(archiveContext, ctx, dm, recursionLevel, true);
					metadata = Metadata.construct(metadataBucket);
					return runMetadata(dm, recursionLevel+1, key, metaStrings, metadata, container, thisKey, dontEnterImplicitArchives);
				}
				Bucket result = container.get(metadata.getZIPInternalName(), archiveContext, ctx, dm, recursionLevel, true);
				dm.mergeNoOverwrite(metadata.getClientMetadata());
				return new FetchResult(dm, result);
			}
		} else if(metadata.isMultiLevelMetadata()) {
			// Doesn't have to be a splitfile; could be from a ZIP or a plain file.
			metadata.setSimpleRedirect();
			FetchResult res = runMetadata(dm, recursionLevel, key, metaStrings, metadata, container, thisKey, true);
			metadata = Metadata.construct(res.data);
			return runMetadata(dm, recursionLevel, key, metaStrings, metadata, container, thisKey, dontEnterImplicitArchives);
		} else if(metadata.isSingleFileRedirect()) {
			FreenetURI uri = metadata.getSingleTarget();
			dm.mergeNoOverwrite(metadata.getClientMetadata());
			if((!dontEnterImplicitArchives) && ArchiveManager.isUsableArchiveType(dm.getMIMEType()) && (!metaStrings.isEmpty())) {
				ClientKey target = ClientKey.getBaseKey(uri);
				if(!(target.isMetadata())) {
					// Target *is not* metadata.
					// Therefore target is a usable archive.
					// We might not have to fetch it.
					container = ctx.archiveManager.makeHandler(uri, ArchiveManager.getArchiveType(dm.getMIMEType()));
					Bucket metadataBucket = container.getMetadata(archiveContext, ctx, dm, recursionLevel, true);
					metadata = Metadata.construct(metadataBucket);
					return runMetadata(dm, recursionLevel+1, key, metaStrings, metadata, container, thisKey, dontEnterImplicitArchives);
				}
			}
			return realRun(dm, recursionLevel, uri, dontEnterImplicitArchives);
		} else if(metadata.isSplitfile()) {
			// Straight data splitfile.
			// Might be used by parents for something else, in which case they will set dontEnterImplicitArchives.
			dm.mergeNoOverwrite(metadata.getClientMetadata()); // even splitfiles can have mime types!
			if((!dontEnterImplicitArchives) && ArchiveManager.isUsableArchiveType(dm.getMIMEType()) && (!metaStrings.isEmpty())) {
				// We know target is not metadata.
				container = ctx.archiveManager.makeHandler(thisKey, ArchiveManager.getArchiveType(dm.getMIMEType()));
				Bucket metadataBucket = container.getMetadata(archiveContext, ctx, dm, recursionLevel, true);
				metadata = Metadata.construct(metadataBucket);
				return runMetadata(dm, recursionLevel+1, key, metaStrings, metadata, container, thisKey, dontEnterImplicitArchives);
			}
			
			SplitFetcher sf = new SplitFetcher(metadata, ctx.maxTempLength, archiveContext, ctx);
			Bucket sfResult = sf.fetch(); // will throw in event of error
			return new FetchResult(dm, sfResult);
		} else {
			Logger.error(this, "Don't know what to do with metadata: "+metadata);
			throw new FetchException(FetchException.UNKNOWN_METADATA);
		}
	}
}
