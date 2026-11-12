package cz.xprees.comark.format.studio

/**
 * The HTML attribute table of property-information 7.2.0 (MIT, Copyright (c) Titus Wormer), as hast-util-raw uses it to
 * turn hast property names into HTML attributes and back (`find(html, name)`): each entry is
 * `property=attribute:flags`, flags being b (boolean), B (booleanish), o (overloadedBoolean), n (number),
 * s (spaceSeparated), c (commaSeparated), C (commaOrSpaceSeparated) or - (a plain string). Generated from
 * `node_modules/property-information` of src/test/node/studio-oracle.
 */
internal object StudioHastProperties {
    class Info(val property: String, val attribute: String, val flags: String) {
        val plain: Boolean get() = flags == "-" && property == attribute
    }

    /** Keyed by the lowercased attribute name and the lowercased property name (property-information `normal`). */
    val normal: Map<String, Info> by lazy {
        val m = HashMap<String, Info>()
        for (entry in TABLE.split(" ")) {
            val (property, rest) = entry.split("=")
            val (attribute, flags) = rest.split(":")
            val info = Info(property, attribute, flags)
            m[property.lowercase()] = info
            m[attribute.lowercase()] = info
        }
        m
    }

    private val TABLE = listOf(
        "aLink=alink:- abbr=abbr:- accept=accept:c acceptCharset=accept-charset:s accessKey=accesskey:s",
        "action=action:- align=align:- allow=allow:- allowFullScreen=allowfullscreen:b",
        "allowPaymentRequest=allowpaymentrequest:b allowTransparency=allowtransparency:-",
        "allowUserMedia=allowusermedia:b alpha=alpha:b alt=alt:- archive=archive:s",
        "ariaActiveDescendant=aria-activedescendant:- ariaAtomic=aria-atomic:B",
        "ariaAutoComplete=aria-autocomplete:- ariaBusy=aria-busy:B ariaChecked=aria-checked:B",
        "ariaColCount=aria-colcount:n ariaColIndex=aria-colindex:n ariaColSpan=aria-colspan:n",
        "ariaControls=aria-controls:s ariaCurrent=aria-current:- ariaDescribedBy=aria-describedby:s",
        "ariaDetails=aria-details:- ariaDisabled=aria-disabled:B ariaDropEffect=aria-dropeffect:s",
        "ariaErrorMessage=aria-errormessage:- ariaExpanded=aria-expanded:B ariaFlowTo=aria-flowto:s",
        "ariaGrabbed=aria-grabbed:B ariaHasPopup=aria-haspopup:- ariaHidden=aria-hidden:B",
        "ariaInvalid=aria-invalid:- ariaKeyShortcuts=aria-keyshortcuts:- ariaLabel=aria-label:-",
        "ariaLabelledBy=aria-labelledby:s ariaLevel=aria-level:n ariaLive=aria-live:- ariaModal=aria-modal:B",
        "ariaMultiLine=aria-multiline:B ariaMultiSelectable=aria-multiselectable:B",
        "ariaOrientation=aria-orientation:- ariaOwns=aria-owns:s ariaPlaceholder=aria-placeholder:-",
        "ariaPosInSet=aria-posinset:n ariaPressed=aria-pressed:B ariaReadOnly=aria-readonly:B",
        "ariaRelevant=aria-relevant:- ariaRequired=aria-required:B ariaRoleDescription=aria-roledescription:s",
        "ariaRowCount=aria-rowcount:n ariaRowIndex=aria-rowindex:n ariaRowSpan=aria-rowspan:n",
        "ariaSelected=aria-selected:B ariaSetSize=aria-setsize:n ariaSort=aria-sort:-",
        "ariaValueMax=aria-valuemax:n ariaValueMin=aria-valuemin:n ariaValueNow=aria-valuenow:n",
        "ariaValueText=aria-valuetext:- as=as:- async=async:b autoCapitalize=autocapitalize:-",
        "autoComplete=autocomplete:s autoCorrect=autocorrect:- autoFocus=autofocus:b autoPlay=autoplay:b",
        "autoSave=autosave:- axis=axis:- background=background:- bgColor=bgcolor:- blocking=blocking:s",
        "border=border:n borderColor=bordercolor:- bottomMargin=bottommargin:n capture=capture:-",
        "cellPadding=cellpadding:- cellSpacing=cellspacing:- char=char:- charOff=charoff:- charSet=charset:-",
        "checked=checked:b cite=cite:- classId=classid:- className=class:s clear=clear:- closedBy=closedby:-",
        "code=code:- codeBase=codebase:- codeType=codetype:- colSpan=colspan:n color=color:-",
        "colorSpace=colorspace:- cols=cols:n command=command:- commandFor=commandfor:- compact=compact:b",
        "content=content:- contentEditable=contenteditable:B controls=controls:b controlsList=controlslist:s",
        "coords=coords:nc credentialless=credentialless:b crossOrigin=crossorigin:- data=data:-",
        "dateTime=datetime:- declare=declare:b decoding=decoding:- default=default:b defer=defer:b dir=dir:-",
        "dirName=dirname:- disablePictureInPicture=disablepictureinpicture:b",
        "disableRemotePlayback=disableremoteplayback:b disabled=disabled:b download=download:o",
        "draggable=draggable:B encType=enctype:- enterKeyHint=enterkeyhint:- event=event:-",
        "exportParts=exportparts:c face=face:- fetchPriority=fetchpriority:- form=form:-",
        "formAction=formaction:- formEncType=formenctype:- formMethod=formmethod:-",
        "formNoValidate=formnovalidate:b formTarget=formtarget:- frame=frame:- frameBorder=frameborder:-",
        "hSpace=hspace:n headers=headers:s height=height:n hidden=hidden:o high=high:n href=href:-",
        "hrefLang=hreflang:- htmlFor=for:s httpEquiv=http-equiv:s id=id:- imageSizes=imagesizes:-",
        "imageSrcSet=imagesrcset:- inert=inert:b inputMode=inputmode:- integrity=integrity:- is=is:-",
        "isMap=ismap:b itemId=itemid:- itemProp=itemprop:s itemRef=itemref:s itemScope=itemscope:b",
        "itemType=itemtype:s kind=kind:- label=label:- lang=lang:- language=language:-",
        "leftMargin=leftmargin:n link=link:- list=list:- loading=loading:- longDesc=longdesc:- loop=loop:b",
        "low=low:n lowSrc=lowsrc:- manifest=manifest:- marginHeight=marginheight:n marginWidth=marginwidth:n",
        "max=max:- maxLength=maxlength:n media=media:- method=method:- min=min:- minLength=minlength:n",
        "multiple=multiple:b muted=muted:b name=name:- noHref=nohref:b noModule=nomodule:b",
        "noResize=noresize:b noShade=noshade:b noValidate=novalidate:b noWrap=nowrap:b nonce=nonce:-",
        "object=object:- onAbort=onabort:- onAfterPrint=onafterprint:- onAuxClick=onauxclick:-",
        "onBeforeMatch=onbeforematch:- onBeforePrint=onbeforeprint:- onBeforeToggle=onbeforetoggle:-",
        "onBeforeUnload=onbeforeunload:- onBlur=onblur:- onCanPlay=oncanplay:-",
        "onCanPlayThrough=oncanplaythrough:- onCancel=oncancel:- onChange=onchange:- onClick=onclick:-",
        "onClose=onclose:- onContextLost=oncontextlost:- onContextMenu=oncontextmenu:-",
        "onContextRestored=oncontextrestored:- onCopy=oncopy:- onCueChange=oncuechange:- onCut=oncut:-",
        "onDblClick=ondblclick:- onDrag=ondrag:- onDragEnd=ondragend:- onDragEnter=ondragenter:-",
        "onDragExit=ondragexit:- onDragLeave=ondragleave:- onDragOver=ondragover:- onDragStart=ondragstart:-",
        "onDrop=ondrop:- onDurationChange=ondurationchange:- onEmptied=onemptied:- onEnded=onended:-",
        "onError=onerror:- onFocus=onfocus:- onFormData=onformdata:- onHashChange=onhashchange:-",
        "onInput=oninput:- onInvalid=oninvalid:- onKeyDown=onkeydown:- onKeyPress=onkeypress:-",
        "onKeyUp=onkeyup:- onLanguageChange=onlanguagechange:- onLoad=onload:- onLoadEnd=onloadend:-",
        "onLoadStart=onloadstart:- onLoadedData=onloadeddata:- onLoadedMetadata=onloadedmetadata:-",
        "onMessage=onmessage:- onMessageError=onmessageerror:- onMouseDown=onmousedown:-",
        "onMouseEnter=onmouseenter:- onMouseLeave=onmouseleave:- onMouseMove=onmousemove:-",
        "onMouseOut=onmouseout:- onMouseOver=onmouseover:- onMouseUp=onmouseup:- onOffline=onoffline:-",
        "onOnline=ononline:- onPageHide=onpagehide:- onPageShow=onpageshow:- onPaste=onpaste:-",
        "onPause=onpause:- onPlay=onplay:- onPlaying=onplaying:- onPopState=onpopstate:-",
        "onProgress=onprogress:- onRateChange=onratechange:- onRejectionHandled=onrejectionhandled:-",
        "onReset=onreset:- onResize=onresize:- onScroll=onscroll:- onScrollEnd=onscrollend:-",
        "onSecurityPolicyViolation=onsecuritypolicyviolation:- onSeeked=onseeked:- onSeeking=onseeking:-",
        "onSelect=onselect:- onSlotChange=onslotchange:- onStalled=onstalled:- onStorage=onstorage:-",
        "onSubmit=onsubmit:- onSuspend=onsuspend:- onTimeUpdate=ontimeupdate:- onToggle=ontoggle:-",
        "onUnhandledRejection=onunhandledrejection:- onUnload=onunload:- onVolumeChange=onvolumechange:-",
        "onWaiting=onwaiting:- onWheel=onwheel:- open=open:b optimum=optimum:n part=part:s pattern=pattern:-",
        "ping=ping:s placeholder=placeholder:- playsInline=playsinline:b popover=popover:-",
        "popoverTarget=popovertarget:- popoverTargetAction=popovertargetaction:- poster=poster:-",
        "prefix=prefix:- preload=preload:- profile=profile:- prompt=prompt:- property=property:-",
        "readOnly=readonly:b referrerPolicy=referrerpolicy:- rel=rel:s required=required:b results=results:n",
        "rev=rev:- reversed=reversed:b rightMargin=rightmargin:n role=role:- rowSpan=rowspan:n rows=rows:n",
        "rules=rules:- sandbox=sandbox:s scheme=scheme:- scope=scope:- scoped=scoped:b scrolling=scrolling:B",
        "seamless=seamless:b security=security:- selected=selected:b shadowRootClonable=shadowrootclonable:b",
        "shadowRootCustomElementRegistry=shadowrootcustomelementregistry:b",
        "shadowRootDelegatesFocus=shadowrootdelegatesfocus:b shadowRootMode=shadowrootmode:-",
        "shadowRootSerializable=shadowrootserializable:b shape=shape:- size=size:n sizes=sizes:- slot=slot:-",
        "span=span:n spellCheck=spellcheck:B src=src:- srcDoc=srcdoc:- srcLang=srclang:- srcSet=srcset:-",
        "standby=standby:- start=start:n step=step:- style=style:- summary=summary:- tabIndex=tabindex:n",
        "target=target:- text=text:- title=title:- topMargin=topmargin:n translate=translate:- type=type:-",
        "typeMustMatch=typemustmatch:b unselectable=unselectable:- useMap=usemap:- vAlign=valign:-",
        "vLink=vlink:- vSpace=vspace:n value=value:B valueType=valuetype:- version=version:- width=width:n",
        "wrap=wrap:- writingSuggestions=writingsuggestions:- xLinkActuate=xlink:actuate:-",
        "xLinkArcRole=xlink:arcrole:- xLinkHref=xlink:href:- xLinkRole=xlink:role:- xLinkShow=xlink:show:-",
        "xLinkTitle=xlink:title:- xLinkType=xlink:type:- xmlBase=xml:base:- xmlLang=xml:lang:-",
        "xmlSpace=xml:space:- xmlns=xmlns:- xmlnsXLink=xmlns:xlink:-",
    ).joinToString(" ")
}
